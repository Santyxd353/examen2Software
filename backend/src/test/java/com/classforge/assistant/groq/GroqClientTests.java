package com.classforge.assistant.groq;

import com.classforge.assistant.llm.AssistantChatClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GroqClientTests {
    HttpServer server;
    String url;
    final JsonMapper json = JsonMapper.builder().build();
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    void reply(String path, int status, String response, String retryAfter) {
        server.createContext("/openai/v1/" + path, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test void chatAuthenticatesServerSideAndReturnsJsonWithoutPuttingTheKeyInThePayload() {
        reply("chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"Respuesta\"}}]}", null);
        var client = new GroqClient(json, url, "test-only-secret", 1000);
        var result = client.chat(Map.of("model", "openai/gpt-oss-120b", "messages", java.util.List.of()));
        assertEquals("Bearer test-only-secret", authorization.get());
        assertFalse(body.get().contains("test-only-secret"));
        assertEquals("Respuesta", result.get("choices").get(0).get("message").get("content").asString());
    }

    @Test void sharedChatContractRetainsAuthenticatedGroqTransport() {
        reply("chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"Respuesta\"}}]}", null);
        AssistantChatClient client = assertInstanceOf(AssistantChatClient.class,
                new GroqClient(json, url, "test-only-secret", 1000));

        var result = client.chat(Map.of("model", "openai/gpt-oss-120b", "messages", java.util.List.of()));

        assertEquals("Respuesta", result.get("choices").get(0).get("message").get("content").asString());
        assertEquals("Bearer test-only-secret", authorization.get());
    }

    @Test void noKeyFailsBeforeNetworkAndDoesNotPretendToBeConfigured() {
        var client = new GroqClient(json, url, "  ", 1000);
        assertFalse(client.isConfigured());
        var error = assertThrows(GroqApiException.class, () -> client.chat(Map.of()));
        assertEquals(503, error.statusCode());
        assertTrue(error.getMessage().contains("GROQ_API_KEY"));
        assertNull(authorization.get());
    }

    @Test void preserves429RetryAfterWithoutLeakingProviderErrorBodyOrKey() {
        reply("chat/completions", 429, "{\"error\":{\"message\":\"secret-key-and-user-data\"}}", "17");
        var error = assertThrows(GroqApiException.class, () -> new GroqClient(json, url, "test-key", 1000).chat(Map.of()));
        assertEquals(429, error.statusCode());
        assertEquals(17, error.retryAfterSeconds());
        assertFalse(error.getMessage().contains("secret-key-and-user-data"));
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test void upstreamAuthenticationFailureIsSanitized() {
        reply("chat/completions", 401, "{\"error\":{\"message\":\"sensitive upstream details\"}}", null);
        var error = assertThrows(GroqApiException.class, () -> new GroqClient(json, url, "test-key", 1000).chat(Map.of()));
        assertEquals(401, error.statusCode());
        assertFalse(error.getMessage().contains("sensitive"));
    }

    @Test void speechSendsMultipartFileModelAndSpanishLanguageToGroq() {
        reply("audio/transcriptions", 200, "{\"text\":\"Crea una clase Pedido\"}", null);
        var response = new GroqClient(json, url, "test-key", 1000).transcribe(new byte[]{1,2,3},
                "audio/wav", "recording.wav", "whisper-large-v3-turbo", "es");
        assertEquals("Crea una clase Pedido", response.get("text").asString());
        assertEquals("Bearer test-key", authorization.get());
        for (String part : new String[]{"name=\"file\"", "recording.wav", "name=\"model\"", "whisper-large-v3-turbo", "name=\"language\"", "es"}) {
            assertTrue(body.get().contains(part), part);
        }
        assertFalse(body.get().contains("test-key"));
    }

    @Test void modelsUsesAuthenticatedGroqEndpointAndMalformedJsonFailsClosed() {
        reply("models", 200, "not json", null);
        var error = assertThrows(GroqApiException.class, () -> new GroqClient(json, url, "test-key", 1000).models());
        assertEquals(502, error.statusCode());
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test void totalTimeoutIncludesTheBodyAfterHeadersHaveAlreadyArrived() throws Exception {
        var headersSent = new java.util.concurrent.CountDownLatch(1);
        var releaseBody = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/openai/v1/models", exchange -> {
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            headersSent.countDown();
            try {
                releaseBody.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* expected when the deadline cancels the request */ }
            finally { exchange.close(); }
        });
        try {
            var error = assertThrows(GroqApiException.class,
                    () -> new GroqClient(json, url, "test-key", 300).models());
            assertEquals(503, error.statusCode());
            assertEquals(0, headersSent.getCount(), "The test must reach headers before stalling the body");
        } finally { releaseBody.countDown(); }
    }
}
