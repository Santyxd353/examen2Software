package com.classforge.generation.spring.assistant;

import com.classforge.generation.spring.application.SpringBootGenerationOptions;
import com.classforge.generation.spring.generated.GeneratedProjectValidator;
import com.classforge.generation.spring.rendering.SpringFreeMarkerRenderer;
import com.classforge.generation.spring.rendering.SpringProjectRenderer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
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

import static org.junit.jupiter.api.Assertions.*;

class GeneratedAssistantCodexTransportTests {
    @TempDir Path temp;

    @ParameterizedTest
    @CsvSource({"com.classforge,com.classforge.assistant.runtime", "org.classforge,org.classforge.assistant.runtime"})
    void propertyAdaptationPreservesClassforgeRuntimePackagesAndImports(String basePackage, String runtimePackage) throws Exception {
        var generated = new GeneratedAssistantRuntimeRenderer().render(basePackage + ".assistant");
        Path root = Files.createDirectories(temp.resolve(basePackage));
        List<Path> sources = new ArrayList<>();
        String runtimePath = "src/main/java/" + runtimePackage.replace('.', '/') + "/";
        for (var file : generated) {
            assertTrue(file.path().startsWith(runtimePath), file.path());
            String source = new String(file.content(), StandardCharsets.UTF_8);
            String relative = file.path().substring(runtimePath.length());
            String expectedPackage = runtimePackage + (relative.contains("/")
                    ? "." + relative.substring(0, relative.lastIndexOf('/')).replace('/', '.') : "");
            assertTrue(source.startsWith("package " + expectedPackage + ";\n"), file.path());
            if (relative.equals("codex/CodexCompletionClient.java")) {
                assertTrue(source.contains("import " + runtimePackage + ".AssistantRuntimeStatus;"));
                assertTrue(source.contains("import " + runtimePackage + ".llm.AssistantChatClient;"));
                assertTrue(source.contains("${app.assistant.codex.model:gpt-6.1-sol}"));
                assertFalse(source.contains("${classforge.assistant."));
            }
            Path target = root.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.write(target, file.content());
            sources.add(target);
        }
        Path classes = Files.createDirectories(root.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
            assertTrue(compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classpath(), "-d", classes.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call(), () -> diagnostics.getDiagnostics().toString());
        }
    }

    @Test
    void exportedRuntimeCompilesStandaloneAndDefaultsToCodexWithLocalWhisperWithoutApiKey() throws Exception {
        var fixture = new GeneratedAssistantRenderingTests().fixture();
        var project = new SpringProjectRenderer(new SpringFreeMarkerRenderer(), new GeneratedProjectValidator())
                .render(fixture.model(), SpringBootGenerationOptions.simpleCrud(false, "#7C3AED"));
        String assistantPath = "src/main/java/" + fixture.model().basePackage().replace('.', '/') + "/assistant/";
        List<Path> sources = new ArrayList<>();
        for (var file : project.files()) {
            if (!file.path().endsWith(".java") || !file.path().startsWith(assistantPath)) continue;
            String source = new String(file.content(), StandardCharsets.UTF_8);
            assertFalse(source.contains("com.classforge.assistant"), file.path());
            Path target = temp.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.write(target, file.content());
            sources.add(target);
        }
        assertTrue(project.files().stream().anyMatch(file -> file.path().endsWith("/runtime/codex/CodexProcessRunner.java")),
                "The export must include the shared bounded process transport");
        Path classes = Files.createDirectories(temp.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
            assertTrue(compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classpath(), "-d", classes.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call(), () -> diagnostics.getDiagnostics().toString());
        }
        AtomicReference<String> body = new AtomicReference<>(), authorization = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inference", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"text\":\"Cuenta los usuarios\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> clientType = loader.loadClass(fixture.model().basePackage() + ".assistant.GeneratedAssistantGroqClient");
            new ApplicationContextRunner().withUserConfiguration(clientType)
                    .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
                    .withPropertyValues("app.assistant.whisper-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        Object client = context.getBean(clientType);
                        assertEquals("codex", clientType.getMethod("providerName").invoke(client));
                        assertEquals("whisper-cpp", clientType.getMethod("speechProviderName").invoke(client));
                        assertEquals(false, clientType.getMethod("isConfigured").invoke(client));
                        var failure = assertThrows(InvocationTargetException.class,
                                () -> clientType.getMethod("chat", Map.class).invoke(client, Map.of("messages", List.of(
                                        Map.of("role", "user", "content", "Cuenta los usuarios"))))).getCause();
                        assertEquals(503, failure.getClass().getMethod("statusCode").invoke(failure));
                        assertTrue(failure.getMessage().contains("Codex"));
                        byte[] wav = new byte[44];
                        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
                        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
                        var result = (tools.jackson.databind.JsonNode) clientType.getMethod("transcribe", byte[].class, String.class)
                                .invoke(client, wav, "voice.wav");
                        assertEquals("Cuenta los usuarios", result.get("text").asString());
                        assertEquals(1, calls.get());
                        assertNull(authorization.get());
                        assertTrue(body.get().contains("name=\"file\"; filename=\"voice.wav\""));
                        assertTrue(body.get().contains("name=\"language\"\r\n\r\nes"));
                    });
        } finally { server.stop(0); }
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
