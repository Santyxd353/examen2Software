package com.classforge.generation.spring.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.classforge.generation.spring.application.SpringBootGenerationOptions;
import com.classforge.generation.spring.generated.GeneratedProjectValidator;
import com.classforge.generation.spring.rendering.SpringFreeMarkerRenderer;
import com.classforge.generation.spring.rendering.SpringProjectRenderer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Compiles exported assistant Java, then exercises its actual HTTP transport. */
class GeneratedAssistantGroqTransportTests {
    @TempDir Path temp;

    @Test
    void generatedAssistantCompilesAndUsesAuthenticatedGroqJsonMultipartAndSanitizedQuotaErrors() throws Exception {
        var fixture = new GeneratedAssistantRenderingTests().fixture();
        var project = new SpringProjectRenderer(new SpringFreeMarkerRenderer(), new GeneratedProjectValidator())
                .render(fixture.model(), SpringBootGenerationOptions.simpleCrud(false, "#7C3AED"));
        String assistantPath = "src/main/java/" + fixture.model().basePackage().replace('.', '/') + "/assistant/";
        List<Path> sources = new ArrayList<>();
        for (var file : project.files()) {
            if (!file.path().endsWith(".java") || !file.path().startsWith(assistantPath)) continue;
            Path target = temp.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.write(target, file.content());
            sources.add(target);
        }
        Path classes = temp.resolve("classes");
        Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required to verify exported Java");
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
            boolean compiled = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classpath(), "-d", classes.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call();
            assertTrue(compiled, () -> diagnostics.getDiagnostics().toString());
        }
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> clientType = loader.loadClass(fixture.model().basePackage() + ".assistant.GeneratedAssistantGroqClient");
            AtomicReference<String> requestBody = new AtomicReference<>();
            AtomicReference<String> authorization = new AtomicReference<>();
            AtomicReference<String> requestPath = new AtomicReference<>();
            AtomicReference<String> responseJson = new AtomicReference<>("{\"text\":\"voz de prueba\",\"choices\":[]}");
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger status = new AtomicInteger(200);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/openai/v1", exchange -> {
                calls.incrementAndGet();
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestPath.set(exchange.getRequestURI().getPath());
                String response = status.get() == 200 ? responseJson.get()
                        : "{\"error\":\"provider-private-body test-key\"}";
                if (status.get() == 429) exchange.getResponseHeaders().add("Retry-After", "7");
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                var constructor = clientType.getConstructor(JsonMapper.class, String.class, String.class, String.class, String.class, long.class);
                String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1";
                Object client = constructor.newInstance(JsonMapper.builder().build(), baseUrl, "test-key", "speech-test", "es", 2000L);
                var chat = clientType.getMethod("chat", Map.class);
                JsonNode response = (JsonNode) chat.invoke(client, Map.of("model", "text-test", "max_completion_tokens", 100));
                assertTrue(response.has("choices"));
                assertEquals("Bearer test-key", authorization.get());
                assertEquals("/openai/v1/chat/completions", requestPath.get());
                assertTrue(requestBody.get().contains("max_completion_tokens"));
                clientType.getMethod("transcribe", byte[].class, String.class).invoke(client, new byte[]{1, 2}, "voice.wav");
                assertEquals("/openai/v1/audio/transcriptions", requestPath.get());
                assertTrue(requestBody.get().contains("name=\"model\"\r\n\r\nspeech-test"));
                assertTrue(requestBody.get().contains("name=\"file\"; filename=\"voice.wav\""));
                assertTrue(requestBody.get().contains("name=\"language\"\r\n\r\nes"));
                assertTrue(requestBody.get().contains("name=\"response_format\"\r\n\r\njson"));
                assertFalse(requestBody.get().contains("temperature_inc"));
                Class<?> metadata = loader.loadClass(fixture.model().basePackage() + ".assistant.GeneratedAssistantMetadata");
                String entity = ((List<?>) metadata.getMethod("entityCodes").invoke(null)).getFirst().toString();
                JsonMapper mapper = JsonMapper.builder().build();
                String routeArguments = mapper.writeValueAsString(Map.of("intent", "COUNT", "entity", entity));
                var toolCall = Map.of("type", "function", "function", Map.of("name", "route_data_request", "arguments", routeArguments));
                responseJson.set(mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("tool_calls", List.of(toolCall)))))));
                Class<?> gatewayType = loader.loadClass(fixture.model().basePackage() + ".assistant.GeneratedAssistantLlamaGateway");
                Object gateway = gatewayType.getConstructor(JsonMapper.class, clientType, String.class).newInstance(mapper, client, "text-test");
                gatewayType.getMethod("route", String.class).invoke(gateway, "Cuenta los usuarios");
                JsonNode request = mapper.readTree(requestBody.get());
                assertEquals("required", request.path("tool_choice").asString());
                assertEquals("function", request.path("tools").path(0).path("type").asString());
                assertEquals(1, request.path("tools").size());
                assertTrue(request.has("max_completion_tokens"));
                assertFalse(request.has("max_tokens"));
                assertFalse(request.has("builtin_tools"));
                status.set(429);
                int beforeQuota = calls.get();
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> chat.invoke(client, Map.of("model", "text-test"))).getCause();
                assertEquals(beforeQuota + 1, calls.get(), "No automatic retry on quota exhaustion");
                assertEquals(7L, failure.getClass().getMethod("retryAfterSeconds").invoke(failure));
                assertFalse(failure.getMessage().contains("test-key"));
                assertFalse(failure.getMessage().contains("provider-private-body"));
                Class<?> controllerType = loader.loadClass(fixture.model().basePackage() + ".assistant.GeneratedAssistantController");
                Object controller = controllerType.getConstructors()[0].newInstance(new Object[]{null});
                var errorHandler = controllerType.getMethod("providerUnavailable", failure.getClass());
                var quotaResponse = (org.springframework.http.ResponseEntity<?>) errorHandler.invoke(controller, failure);
                assertEquals(429, quotaResponse.getStatusCode().value());
                assertEquals("7", quotaResponse.getHeaders().getFirst("Retry-After"));
                status.set(401);
                var authenticationFailure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> chat.invoke(client, Map.of("model", "text-test"))).getCause();
                var unavailableResponse = (org.springframework.http.ResponseEntity<?>) errorHandler.invoke(controller, authenticationFailure);
                assertEquals(503, unavailableResponse.getStatusCode().value(), "Provider credentials must not invalidate the application's user JWT");
                Object missingKey = constructor.newInstance(JsonMapper.builder().build(), baseUrl, "", "speech-test", "es", 2000L);
                int beforeMissing = calls.get();
                assertThrows(java.lang.reflect.InvocationTargetException.class, () -> chat.invoke(missingKey, Map.of()));
                assertEquals(beforeMissing, calls.get(), "Unconfigured exported projects must not call the provider");
                assertTotalBodyDeadline(constructor, chat);
            } finally { server.stop(0); }
        }
    }

    private void assertTotalBodyDeadline(java.lang.reflect.Constructor<?> constructor, java.lang.reflect.Method chat) throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer delayed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        delayed.createContext("/openai/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                releaseBody.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write("\"text\":\"late-body\"}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelled) { /* Expected when the client cancels its timed-out request. */ }
            finally { exchange.close(); }
        });
        delayed.start();
        try {
            Object client = constructor.newInstance(JsonMapper.builder().build(),
                    "http://127.0.0.1:" + delayed.getAddress().getPort() + "/openai/v1",
                    "test-key", "speech-test", "es", 350L);
            long started = System.nanoTime();
            CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
                try { chat.invoke(client, Map.of("model", "text-test")); return null; }
                catch (java.lang.reflect.InvocationTargetException failure) { return failure.getCause(); }
                catch (Exception failure) { return failure; }
            });
            assertTrue(headersSent.await(2, TimeUnit.SECONDS), "The provider must have sent headers and a partial body before testing the deadline");
            Throwable failure = result.get(2, TimeUnit.SECONDS);
            assertNotNull(failure, "A stalled response body must fail within the total configured request deadline");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1800, "Response body time belongs to the total deadline");
            assertEquals(503, failure.getClass().getMethod("statusCode").invoke(failure));
            assertFalse(failure.getMessage().contains("test-key"));
            assertEquals(1, calls.get(), "A timed-out request must not be retried automatically");
        } finally {
            releaseBody.countDown();
            delayed.stop(0);
        }
    }

    private String classpath() throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs()) {
                if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }
}
