package com.classforge.generation.spring.assistant;

import com.classforge.generation.spring.domain.DomainManifestPlan;
import java.util.*;
import java.util.stream.Collectors;

final class GeneratedAssistantGatewayRenderer {

    String llama(String pkg) {
        return template("""
                package __PACKAGE__;

                import static __PACKAGE__.GeneratedAssistantTypes.*;

                import java.time.LocalDateTime;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.json.JsonMapper;

                @Component
                public class GeneratedAssistantLlamaGateway {
                    private final JsonMapper json;
                    private final GeneratedAssistantGroqClient groq;
                    private final String model;

                    public GeneratedAssistantLlamaGateway(JsonMapper json, GeneratedAssistantGroqClient groq,
                            @Value("${app.assistant.groq.text-model:openai/gpt-oss-120b}") String model) {
                        this.json = json;
                        this.groq = groq;
                        this.model = groq.textModel(model);
                    }
                    public boolean isConfigured() { return groq.isConfigured(); }
                    public String providerName() { return groq.providerName(); }

                    public RouteDecision route(String text) {
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("intent", Map.of("type", "string", "enum", List.of(
                                "QUERY", "COUNT", "GET", "CREATE", "UPDATE", "DELETE",
                                "SET_RELATION", "ADD_RELATION", "REMOVE_RELATION")));
                        props.put("entity", Map.of("type", "string", "enum", GeneratedAssistantMetadata.entityCodes()));
                        JsonNode args = call("route_data_request", "Clasifica la intencion y la entidad principal sin ejecutar nada.",
                                objectSchema(props, List.of("intent", "entity")),
                                "Eres el router de datos de la aplicacion generada. Usa una sola tool_call. "
                                        + "Elige tambien la entidad principal mencionada por el usuario. "
                                        + "QUERY=listar/buscar; COUNT=contar; GET=un registro; CREATE=crear; UPDATE=modificar; DELETE=eliminar; "
                                        + "SET_RELATION=asignar relacion to-one; ADD_RELATION/REMOVE_RELATION=relacion many-to-many.", text);
                        try {
                            Intent intent = Intent.valueOf(args.path("intent").asString());
                            String entity = args.path("entity").asString();
                            GeneratedAssistantMetadata.entityByCode(entity);
                            return new RouteDecision(intent, entity);
                        } catch (RuntimeException exception) {
                            throw new IllegalArgumentException("El proveedor de IA devolvio una ruta de datos desconocida.", exception);
                        }
                    }

                    public RawCommand command(String text, RouteDecision route) {
                        return command(text, route, null);
                    }

                    public RawCommand command(String text, RouteDecision route, String correction) {
                        Intent intent = route.intent();
                        String entity = route.entity();
                        String tool = switch (intent) {
                            case QUERY -> "query_records"; case COUNT -> "count_records"; case GET -> "get_record";
                            case CREATE -> "create_record"; case UPDATE -> "update_record"; case DELETE -> "delete_record";
                            case SET_RELATION -> "set_relation"; case ADD_RELATION -> "add_relation"; case REMOVE_RELATION -> "remove_relation";
                        };
                        boolean queryIntent = intent == Intent.QUERY || intent == Intent.COUNT;
                        boolean selectorIntent = intent == Intent.GET || intent == Intent.UPDATE || intent == Intent.DELETE
                                || intent == Intent.SET_RELATION || intent == Intent.ADD_RELATION || intent == Intent.REMOVE_RELATION;
                        boolean relationIntent = intent == Intent.SET_RELATION || intent == Intent.ADD_RELATION || intent == Intent.REMOVE_RELATION;
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put("entity", Map.of("type", "string", "enum", List.of(entity)));
                        props.put("query", Map.of("type", "string"));
                        props.put("filters", entriesSchema(queryIntent ? GeneratedAssistantMetadata.filterFieldNames(entity) : List.of()));
                        props.put("selector", entriesSchema(selectorIntent ? GeneratedAssistantMetadata.selectorFieldNames(entity) : List.of()));
                        props.put("values", entriesSchema(GeneratedAssistantMetadata.valueFieldNames(entity, intent)));
                        List<String> relations = relationIntent ? GeneratedAssistantMetadata.relationNames(entity) : List.of();
                        props.put("relation", relations.isEmpty() ? Map.of("type", "string") : Map.of("type", "string", "enum", relations));
                        props.put("targetSelector", entriesSchema(relationIntent ? GeneratedAssistantMetadata.targetSelectorFieldNames(entity) : List.of()));
                        JsonNode args = call(tool, "Prepara un comando grounded para " + intent + " sobre " + entity,
                                objectSchema(props, List.of("entity")), commandPrompt(route, correction), text);
                        return parseRaw(args, entity);
                    }

                    private String commandPrompt(RouteDecision route, String correction) {
                        String repair = correction == null || correction.isBlank() ? ""
                                : " El intento anterior fue rechazado por esta razon: " + correction
                                        + " Corrige solo ese problema y vuelve a emitir la tool_call.";
                        String clock = LocalDateTime.now().withNano(0).toString();
                        return "Eres el planificador de datos de una aplicacion generada por ClassForge. "
                                + "Responde exclusivamente con una tool_call y trabaja SOLO con la entidad " + route.entity() + ". "
                                + "No inventes entidades, campos ni relaciones. Usa selector solo para identificar el registro principal existente; "
                                + "values solo para datos que se escriben. Si el usuario menciona una relacion to-one por un nombre humano, "
                                + "por ejemplo 'propietario Hola', coloca en values el nombre de la relacion ('propietario') y el valor humano ('Hola'); "
                                + "NO inventes campos tecnicos como propietarioId ni intentes convertir nombres humanos a UUID. "
                                + "REGLA ESTRICTA DE FECHAS: el reloj local actual del servidor es " + clock + ". "
                                + "Si el usuario dice hoy, ahora, ayer, manana, pasado manana, anteayer, hace N dias/semanas/meses/anos/horas/minutos "
                                + "o dentro de N dias/semanas/meses/anos/horas/minutos, NO calcules una fecha absoluta. "
                                + "Copia esa expresion temporal como value del campo DATE/DATETIME; puedes convertir 'un/una' a '1', pero nada mas. "
                                + "Ejemplos: 'creado hoy' -> value='hoy'; 'nacido hace un ano' -> value='hace 1 ano'. "
                                + "Si el usuario da una fecha absoluta sin hora para un DATETIME, conserva YYYY-MM-DD; el backend completara una hora valida. "
                                + "targetSelector se usa solo con SET_RELATION/ADD_RELATION/REMOVE_RELATION. Para null escribe literalmente null. "
                                + "Para IDs compuestos usa campo=valor separado por comas cuando el valor sea un identificador directo. Intent="
                                + route.intent() + ". Catalogo acotado:" + GeneratedAssistantMetadata.catalogPrompt(route.entity(), route.intent()) + repair;
                    }

                    private RawCommand parseRaw(JsonNode node, String entityFallback) {
                        String entity = text(node, "entity");
                        if (entity == null || entity.isBlank()) entity = entityFallback;
                        return new RawCommand(entity, text(node, "query"), filters(node.get("filters")),
                                values(node.get("selector")), values(node.get("values")), text(node, "relation"), values(node.get("targetSelector")));
                    }
                    private List<FilterInput> filters(JsonNode node) {
                        List<FilterInput> out = new ArrayList<>();
                        if (node != null && node.isArray()) for (JsonNode item : node) out.add(new FilterInput(text(item, "field"), text(item, "value")));
                        return out;
                    }
                    private List<FieldValue> values(JsonNode node) {
                        List<FieldValue> out = new ArrayList<>();
                        if (node != null && node.isArray()) for (JsonNode item : node) out.add(new FieldValue(text(item, "field"), text(item, "value")));
                        return out;
                    }
                    private String text(JsonNode node, String field) {
                        if (node == null || node.get(field) == null || node.get(field).isNull()) return null;
                        return node.get(field).asString();
                    }

                    private Map<String, Object> entriesSchema(List<String> fields) {
                        Map<String, Object> itemProps = new LinkedHashMap<>();
                        itemProps.put("field", fields.isEmpty() ? Map.of("type", "string") : Map.of("type", "string", "enum", fields));
                        itemProps.put("value", Map.of("type", "string"));
                        Map<String, Object> schema = new LinkedHashMap<>();
                        schema.put("type", "array");
                        schema.put("items", objectSchema(itemProps, List.of("field", "value")));
                        if (fields.isEmpty()) schema.put("maxItems", 0);
                        return schema;
                    }
                    private Map<String, Object> objectSchema(Map<String, Object> props, List<String> required) {
                        Map<String, Object> schema = new LinkedHashMap<>();
                        schema.put("type", "object"); schema.put("properties", props); schema.put("required", required); schema.put("additionalProperties", false);
                        return schema;
                    }

                    private JsonNode call(String toolName, String description, Map<String, Object> parameters, String system, String user) {
                        try {
                            Map<String, Object> function = new LinkedHashMap<>();
                            function.put("name", toolName); function.put("description", description); function.put("parameters", parameters);
                            Map<String, Object> request = new LinkedHashMap<>();
                            request.put("model", model); request.put("temperature", 0.0); request.put("max_completion_tokens", 2048);
                            request.put("stream", false); request.put("parallel_tool_calls", false); request.put("tool_choice", "required");
                            request.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
                            request.put("tools", List.of(Map.of("type", "function", "function", function)));
                            JsonNode root = groq.chat(request);
                            JsonNode calls = root.path("choices").path(0).path("message").path("tool_calls");
                            if (!calls.isArray() || calls.size() != 1) throw new IllegalStateException("El proveedor de IA debe devolver una sola tool_call.");
                            JsonNode fn = calls.path(0).path("function");
                            if (!"function".equals(calls.path(0).path("type").asString()) || !toolName.equals(fn.path("name").asString()))
                                throw new IllegalStateException("El proveedor de IA no uso la herramienta de datos esperada.");
                            JsonNode raw = fn.get("arguments");
                            JsonNode args = raw != null && raw.isTextual() ? json.readTree(raw.asString()) : raw;
                            if (args == null || !args.isObject()) throw new IllegalStateException("El proveedor de IA no produjo argumentos validos.");
                            return args;
                        } catch (GeneratedAssistantGroqClient.ProviderException exception) { throw exception; }
                        catch (Exception exception) { throw new IllegalStateException("El proveedor de IA no devolvio una herramienta de datos valida."); }
                    }

                }
                """, pkg);
    }

    String whisper(String pkg) {
        return template("""
                package __PACKAGE__;

                import org.springframework.stereotype.Component;
                import org.springframework.web.multipart.MultipartFile;
                import tools.jackson.databind.JsonNode;

                @Component
                public class GeneratedAssistantWhisperGateway {
                    public static final int MAX_AUDIO_BYTES = 4 * 1024 * 1024;
                    private final GeneratedAssistantGroqClient groq;
                    public GeneratedAssistantWhisperGateway(GeneratedAssistantGroqClient groq) { this.groq = groq; }
                    public boolean isConfigured() { return groq.isVoiceConfigured(); }
                    public String providerName() { return groq.speechProviderName(); }

                    public String transcribe(MultipartFile audio) {
                        if (audio == null || audio.isEmpty()) throw new IllegalArgumentException("No se recibio audio.");
                        if (audio.getSize() > MAX_AUDIO_BYTES) throw new IllegalArgumentException("El audio supera 4 MB.");
                        try {
                            byte[] bytes = audio.getBytes(); validateWav(bytes);
                            JsonNode root = groq.transcribe(bytes, audio.getOriginalFilename());
                            String transcript = root.path("text").asString().replaceAll("\\\\s+", " ").trim();
                            if (transcript.isBlank()) throw new IllegalArgumentException("Whisper no detecto voz suficiente.");
                            if (transcript.length() > 1000) throw new IllegalArgumentException("La transcripcion supera 1000 caracteres.");
                            return transcript;
                        } catch (GeneratedAssistantGroqClient.ProviderException exception) { throw exception; }
                        catch (IllegalArgumentException exception) { throw exception; }
                        catch (Exception exception) { throw new IllegalStateException("No se pudo transcribir el audio."); }
                    }
                    private void validateWav(byte[] bytes) {
                        if (bytes == null || bytes.length < 44 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
                                || bytes[8] != 'W' || bytes[9] != 'A' || bytes[10] != 'V' || bytes[11] != 'E')
                            throw new IllegalArgumentException("Se esperaba audio WAV PCM.");
                    }
                }
                """, pkg);
    }

    String groqClient(String pkg) {
        return template("""
                package __PACKAGE__;

                import __PACKAGE__.runtime.codex.CodexCompletionClient;
                import __PACKAGE__.runtime.codex.CodexApiException;
                import __PACKAGE__.runtime.WhisperCppSpeechToTextGateway;
                import java.io.ByteArrayOutputStream;
                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;
                import java.nio.charset.StandardCharsets;
                import java.time.Duration;
                import java.time.Instant;
                import java.time.ZonedDateTime;
                import java.time.format.DateTimeFormatter;
                import java.util.Map;
                import java.util.UUID;
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.TimeUnit;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;
                import tools.jackson.databind.JsonNode;
                import tools.jackson.databind.json.JsonMapper;

                /** Backend-only provider facade; the legacy constructor retains explicit Groq compatibility. */
                @Component
                public class GeneratedAssistantGroqClient {
                    private final JsonMapper json;
                    private final HttpClient http;
                    private final String url;
                    private final String apiKey;
                    private final String speechModel;
                    private final String language;
                    private final Duration timeout;
                    private CodexCompletionClient codex;
                    private WhisperCppSpeechToTextGateway whisper;
                    private String provider = "groq", speechProvider = "groq", codexModel, whisperUrl;

                    @Autowired
                    public GeneratedAssistantGroqClient(JsonMapper json,
                            @Value("${app.assistant.text.provider:codex}") String provider,
                            @Value("${app.assistant.voice.provider:whisper-cpp}") String speechProvider,
                            @Value("${app.assistant.codex.executable:codex}") String executable,
                            @Value("${app.assistant.codex.home:}") String home,
                            @Value("${app.assistant.codex.model:gpt-6.1-sol}") String model,
                            @Value("${app.assistant.codex.request-timeout-millis:90000}") long codexTimeout,
                            @Value("${app.assistant.whisper-url:http://127.0.0.1:8093}") String whisperUrl,
                            @Value("${app.assistant.whisper-language:es}") String whisperLanguage,
                            @Value("${app.assistant.groq.base-url:https://api.groq.com/openai/v1}") String url,
                            @Value("${app.assistant.groq.api-key:}") String apiKey,
                            @Value("${app.assistant.groq.speech-model:whisper-large-v3-turbo}") String speechModel,
                            @Value("${app.assistant.groq.language:es}") String language,
                            @Value("${app.assistant.groq.request-timeout-millis:90000}") long timeoutMillis) {
                        this(json, url, apiKey, speechModel, language, timeoutMillis);
                        if (!java.util.Set.of("codex", "groq").contains(provider)
                                || !java.util.Set.of("whisper-cpp", "groq").contains(speechProvider))
                            throw new IllegalArgumentException("Proveedor del asistente no admitido.");
                        this.provider = provider; this.speechProvider = speechProvider;
                        this.codexModel = model; this.whisperUrl = whisperUrl.replaceAll("/+$", "");
                        this.codex = new CodexCompletionClient(json, executable, home, model, codexTimeout);
                        this.whisper = new WhisperCppSpeechToTextGateway(json, whisperUrl, whisperLanguage);
                    }

                    public GeneratedAssistantGroqClient(JsonMapper json,
                            @Value("${app.assistant.groq.base-url:https://api.groq.com/openai/v1}") String url,
                            @Value("${app.assistant.groq.api-key:}") String apiKey,
                            @Value("${app.assistant.groq.speech-model:whisper-large-v3-turbo}") String speechModel,
                            @Value("${app.assistant.groq.language:es}") String language,
                            @Value("${app.assistant.groq.request-timeout-millis:90000}") long timeoutMillis) {
                        this.json = json;
                        this.url = (url == null || url.isBlank() ? "https://api.groq.com/openai/v1" : url.trim()).replaceAll("/+$", "");
                        this.apiKey = apiKey == null ? "" : apiKey.trim();
                        this.speechModel = speechModel;
                        this.language = language == null || language.isBlank() ? "es" : language.trim();
                        this.timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
                        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(10000, Math.max(1, timeoutMillis)))).build();
                    }

                    public String providerName() { return provider; }
                    public String speechProviderName() { return speechProvider; }
                    public String textModel(String legacyModel) { return "codex".equals(provider) ? codexModel : legacyModel; }
                    public boolean isConfigured() { return "codex".equals(provider) ? codex.probe("codex-text").available() : !apiKey.isBlank(); }
                    public boolean isVoiceConfigured() {
                        if ("groq".equals(speechProvider)) return !apiKey.isBlank();
                        CompletableFuture<HttpResponse<Void>> pending = null;
                        try {
                            var request = HttpRequest.newBuilder(URI.create(whisperUrl + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
                            pending = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
                            int status = pending.get(2, TimeUnit.SECONDS).statusCode();
                            return status >= 200 && status < 300;
                        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
                        catch (Exception unavailable) { return false; }
                        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
                    }

                    public JsonNode chat(Map<String, Object> payload) {
                        if ("codex".equals(provider)) {
                            try { return codex.chat(payload); }
                            catch (CodexApiException failure) { throw new ProviderException(failure.statusCode(), failure.retryAfterSeconds(), failure.getMessage()); }
                        }
                        requireConfigured();
                        try {
                            return send("/chat/completions", "application/json", json.writeValueAsBytes(payload));
                        } catch (ProviderException exception) { throw exception; }
                        catch (Exception exception) { throw new ProviderException(503, 0, "No se pudo preparar la solicitud Groq."); }
                    }

                    public JsonNode transcribe(byte[] audio, String filename) {
                        if ("whisper-cpp".equals(speechProvider)) {
                            try { return json.valueToTree(Map.of("text", whisper.transcribe(audio, filename))); }
                            catch (RuntimeException failure) { throw new ProviderException(503, 0, "No se pudo transcribir el audio con Whisper local."); }
                        }
                        requireConfigured();
                        try {
                            String boundary = "ClassForgeGroq" + UUID.randomUUID().toString().replace("-", "");
                            String safe = filename == null || filename.isBlank() ? "voice.wav" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            field(out, boundary, "model", speechModel);
                            field(out, boundary, "language", language);
                            field(out, boundary, "response_format", "json");
                            field(out, boundary, "prompt", GeneratedAssistantMetadata.speechPrompt());
                            write(out, "--" + boundary + "\\r\\nContent-Disposition: form-data; name=\\\"file\\\"; filename=\\\"" + safe
                                    + "\\\"\\r\\nContent-Type: audio/wav\\r\\n\\r\\n");
                            out.write(audio);
                            write(out, "\\r\\n--" + boundary + "--\\r\\n");
                            return send("/audio/transcriptions", "multipart/form-data; boundary=" + boundary, out.toByteArray());
                        } catch (ProviderException exception) { throw exception; }
                        catch (Exception exception) { throw new ProviderException(503, 0, "No se pudo preparar el audio para Groq."); }
                    }

                    private JsonNode send(String path, String contentType, byte[] body) {
                        CompletableFuture<HttpResponse<String>> pending = null;
                        try {
                            HttpRequest request = HttpRequest.newBuilder(URI.create(url + path)).timeout(timeout)
                                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", contentType)
                                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                            pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                            HttpResponse<String> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                long wait = response.statusCode() == 429 ? retryAfter(response.headers().firstValue("Retry-After").orElse("0")) : 0;
                                throw new ProviderException(response.statusCode(), wait, response.statusCode() == 429
                                        ? "Groq alcanzo su limite de solicitudes. Espera y reintenta manualmente."
                                        : "Groq no pudo completar la solicitud. Comprueba la configuracion del servidor.");
                            }
                            JsonNode responseBody = json.readTree(response.body());
                            if (responseBody == null || !responseBody.isObject()) throw new ProviderException(503, 0, "Groq devolvio una respuesta invalida.");
                            return responseBody;
                        } catch (ProviderException exception) { throw exception; }
                        catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new ProviderException(503, 0, "Se interrumpio la solicitud Groq.");
                        } catch (Exception exception) { throw new ProviderException(503, 0, "No se pudo contactar con Groq. Reintenta manualmente."); }
                        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
                    }

                    private void requireConfigured() {
                        if (apiKey.isBlank()) throw new ProviderException(503, 0, "Configura GROQ_API_KEY en el servidor para usar el asistente.");
                    }
                    private long retryAfter(String value) {
                        try { return Math.max(0, Long.parseLong(value.trim())); }
                        catch (RuntimeException ignored) {
                            try { return Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds() + 1); }
                            catch (RuntimeException invalid) { return 0; }
                        }
                    }
                    private void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
                        write(out, "--" + boundary + "\\r\\nContent-Disposition: form-data; name=\\\"" + name + "\\\"\\r\\n\\r\\n" + value + "\\r\\n");
                    }
                    private void write(ByteArrayOutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.UTF_8)); }

                    public static class ProviderException extends IllegalStateException {
                        private final int statusCode;
                        private final long retryAfterSeconds;
                        public ProviderException(int statusCode, long retryAfterSeconds, String message) {
                            super(message); this.statusCode = statusCode; this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
                        }
                        public int statusCode() { return statusCode; }
                        public long retryAfterSeconds() { return retryAfterSeconds; }
                    }
                }
                """, pkg);
    }

    String template(String text, String pkg) { return text.replace("__PACKAGE__", pkg); }
}
