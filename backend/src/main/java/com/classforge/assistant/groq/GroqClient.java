package com.classforge.assistant.groq;

import com.classforge.assistant.llm.AssistantChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;

/** Server-only Groq REST transport. No retries: callers retain request identity and quota control. */
@Component
public class GroqClient implements AssistantChatClient {
    private final JsonMapper json;
    private final URI base;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public GroqClient(JsonMapper json,
            @Value("${classforge.assistant.groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${classforge.assistant.groq.api-key:}") String apiKey,
            @Value("${classforge.assistant.groq.request-timeout-millis:90000}") long timeoutMillis) {
        this.json = json;
        this.base = URI.create(baseUrl.replaceAll("/+$", "") + "/");
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("GROQ_BASE_URL debe ser una URL HTTP válida sin credenciales.");
        }
        if (!"https".equals(base.getScheme()) && !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(base.getHost())) {
            throw new IllegalArgumentException("Groq requiere HTTPS; HTTP se admite únicamente para pruebas locales.");
        }
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (timeoutMillis <= 0) throw new IllegalArgumentException("El timeout de Groq debe ser positivo.");
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(5000, timeoutMillis)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean isConfigured() { return !apiKey.isBlank(); }

    @Override
    public JsonNode chat(Map<String, Object> payload) {
        return execute(request("chat/completions").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build());
    }

    public JsonNode models() { return execute(request("models").GET().build()); }

    public JsonNode transcribe(byte[] audio, String contentType, String filename, String model, String language) {
        if (audio == null || audio.length == 0 || audio.length > 4 * 1024 * 1024) {
            throw new GroqApiException(400, 0, "El audio debe contener entre 1 byte y 4 MB.");
        }
        String boundary = "classforge-" + UUID.randomUUID();
        try {
            var bytes = new ByteArrayOutputStream();
            field(bytes, boundary, "model", model);
            field(bytes, boundary, "language", language);
            field(bytes, boundary, "response_format", "json");
            String safeFilename = filename == null ? "recording.wav" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            String safeType = contentType != null && contentType.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+") ? contentType : "application/octet-stream";
            bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + safeFilename + "\"\r\nContent-Type: " + safeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            bytes.write(audio);
            bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return execute(request("audio/transcriptions").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build());
        } catch (IOException impossible) {
            throw new GroqApiException(503, 0, "No se pudo preparar el audio.", impossible);
        }
    }

    private void field(ByteArrayOutputStream bytes, String boundary, String name, String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new GroqApiException(400, 0, "La configuración de transcripción no es válida.");
        }
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path) {
        if (!isConfigured()) throw new GroqApiException(503, 0, "Configura GROQ_API_KEY en el backend para utilizar la IA.");
        return HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json");
    }

    private JsonNode execute(HttpRequest request) {
        var pending = http.sendAsync(request, info -> limitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    long retry = retryAfter(response.headers().firstValue("Retry-After").orElse(""));
                    String message = switch (status) {
                        case 401 -> "Groq rechazó la API key. Revisa GROQ_API_KEY en el backend.";
                        case 403 -> "La cuenta Groq no tiene acceso al modelo configurado.";
                        case 429 -> "Se alcanzó la cuota de Groq. Espera antes de reintentar la misma solicitud.";
                        case 400, 404, 413 -> "Groq rechazó la solicitud. Revisa el modelo y los límites de entrada configurados.";
                        default -> "Groq no está disponible temporalmente (HTTP " + status + "). Puedes reintentar.";
                    };
                    throw new GroqApiException(status, retry, message);
                }
                byte[] data = response.body();
                try {
                    JsonNode value = json.readTree(data);
                    if (value == null || !value.isObject()) throw new IllegalArgumentException();
                    return value;
                } catch (Exception invalid) {
                    throw new GroqApiException(502, 0, "Groq devolvió una respuesta JSON inválida.");
                }
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new GroqApiException(503, 0, "Groq superó el tiempo de espera. Puedes reintentar.");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new GroqApiException(503, 0, "La solicitud a Groq se interrumpió.");
        } catch (ExecutionException unavailable) {
            if (unavailable.getCause() instanceof GroqApiException failure) throw failure;
            throw new GroqApiException(503, 0, "No se pudo conectar con Groq. Puedes reintentar.");
        }
    }

    /** Both byte budget and deadline cover the complete body, including on Java 21. */
    private HttpResponse.BodySubscriber<byte[]> limitedBody() {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long received;
            private boolean failed;
            @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            @Override public void onNext(List<ByteBuffer> buffers) {
                if (failed) return;
                for (var buffer : buffers) received += buffer.remaining();
                if (received > 2 * 1024 * 1024) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new GroqApiException(502, 0, "La respuesta de Groq supera el límite permitido."));
                    return;
                }
                delegate.onNext(buffers);
            }
            @Override public void onError(Throwable failure) { if (!failed) delegate.onError(failure); }
            @Override public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }

    private long retryAfter(String value) {
        try { return Math.max(0, Math.min(86400, (long) Math.ceil(Double.parseDouble(value)))); }
        catch (NumberFormatException ignored) {
            try { return Math.max(0, Math.min(86400, Duration.between(Instant.now(), ZonedDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds())); }
            catch (Exception absent) { return 0; }
        }
    }
}
