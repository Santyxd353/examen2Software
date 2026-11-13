package com.classforge.assistant.codex;

import com.classforge.assistant.llm.AssistantChatClient;
import com.classforge.assistant.vision.AssistantImageInputValidator;
import com.classforge.assistant.vision.VisionImageNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CodexCompletionClientTests {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<List<String>> command = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> environment = new AtomicReference<>();
    private final AtomicReference<Path> job = new AtomicReference<>();
    private final AtomicReference<JsonNode> schema = new AtomicReference<>();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicReference<String> receivedPrompt = new AtomicReference<>();

    private Map<String, Object> textPayload(String content) {
        return Map.of("model", "untrusted-payload-model", "messages", List.of(Map.of("role", "user", "content", content)));
    }

    private CodexCompletionClient client(String output, String events, int exit, long delay, long timeout) throws Exception {
        Path home = Files.createDirectories(temporary.resolve("private-codex"));
        return new CodexCompletionClient(json, "codex", home.toString(), "gpt-6.1-sol", timeout,
                (arguments, directory, variables) -> {
                    command.set(List.copyOf(arguments));
                    environment.set(Map.copyOf(variables));
                    job.set(directory);
                    if (arguments.contains("--output-schema")) {
                        schema.set(json.readTree(Files.readString(Path.of(argument(arguments, "--output-schema")))));
                    }
                    return startHarness(arguments, directory, variables, output, events, exit, delay);
                });
    }

    private CodexCompletionClient client(String output) throws Exception {
        return client(output, "{\"type\":\"turn.completed\"}\n", 0, 0, 5000);
    }

    @Test void conversationUsesConfiguredModelAndReturnsTheExistingEnvelope() throws Exception {
        AssistantChatClient client = client("{\"content\":\"Una respuesta útil\"}");
        JsonNode result = client.chat(textPayload("¿Qué significa esta clase?"));
        assertEquals("Una respuesta útil", result.at("/choices/0/message/content").asString());
        assertEquals("stop", result.at("/choices/0/finish_reason").asString());
        assertEquals("gpt-6.1-sol", argument(command.get(), "--model"));
        assertFalse(command.get().contains("untrusted-payload-model"));
        assertEquals("string", schema.get().at("/properties/content/type").asString());
        assertEquals(List.of("content"), json.convertValue(schema.get().get("required"), List.class));
        assertFalse(Files.exists(job.get()), "The private request directory must be cleaned after success");
    }

    @Test void processReceivesNoInheritedApplicationCredentialsAndHasNoAgentTools() throws Exception {
        client("{\"content\":\"OK\"}").chat(textPayload("Hola"));
        Map<String, String> values = environment.get();
        assertEquals(temporary.resolve("private-codex").toString(), values.get("CODEX_HOME"));
        assertEquals(job.get().resolve("user-home").toString(), values.get("HOME"));
        for (String key : values.keySet()) assertFalse(key.matches("(?i).*(KEY|TOKEN|SECRET|MONGO|JWT|VAPID|PROXY).*"), key);
        assertTrue(command.get().contains("--ignore-user-config"));
        assertTrue(command.get().contains("--ephemeral"));
        assertFalse(command.get().contains("--ignore-rules"));
        for (String setting : List.of("forced_login_method=chatgpt", "web_search=disabled", "mcp_servers={}",
                "features.shell_tool=false", "features.apps=false", "features.plugins=false", "features.multi_agent=false",
                "features.view_image=false", "features.hooks=false", "features.skip_host_skill_discovery=true",
                "tools.update_plan.enabled=false", "tools.experimental_request_user_input.enabled=false")) {
            assertTrue(command.get().contains(setting), setting);
        }
    }

    @Test void toolOutputRetainsFalseAndRemovesOnlyNullOptionalsIntroducedByStrictMode() throws Exception {
        Map<String, Object> parameters = new LinkedHashMap<>(Map.of("type", "object", "additionalProperties", false,
                "required", List.of("name"), "properties", Map.of("name", Map.of("type", "string"),
                        "nullable", Map.of("type", "boolean"), "explicitNull", Map.of("type", List.of("string", "null")),
                        "attributes", Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", false,
                                "required", List.of("name"), "properties", Map.of("name", Map.of("type", "string"), "label", Map.of("type", "string")))))));
        Map<String, Object> payload = new LinkedHashMap<>(textPayload("Crea Pedido"));
        payload.put("tools", List.of(Map.of("type", "function", "function", Map.of("name", "create_class", "parameters", parameters))));
        JsonNode result = client("{\"name\":\"create_class\",\"arguments\":{\"name\":\"Pedido\",\"nullable\":false,\"explicitNull\":null,\"attributes\":[{\"name\":\"id\",\"label\":null}]}}").chat(payload);
        JsonNode calls = result.at("/choices/0/message/tool_calls");
        assertEquals(1, calls.size());
        assertEquals("create_class", calls.at("/0/function/name").asString());
        JsonNode args = json.readTree(calls.at("/0/function/arguments").asString());
        assertFalse(args.get("nullable").asBoolean());
        assertTrue(args.has("explicitNull"));
        assertFalse(args.at("/attributes/0").has("label"));
        assertEquals(List.of("name"), parameters.get("required"), "The caller's schema must remain unchanged");
        assertEquals(4, schema.get().at("/properties/arguments/required").size());
    }

    @Test void validatesTheSelectedToolAgainstItsOwnDynamicCatalogSchema() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(textPayload("Opera"));
        payload.put("tools", List.of(tool("rename", Map.of("target", Map.of("type", "string", "enum", List.of("Pedido")))),
                tool("delete", Map.of("target", Map.of("type", "string", "enum", List.of("Cliente"))))));
        var error = assertThrows(CodexApiException.class,
                () -> client("{\"name\":\"rename\",\"arguments\":{\"target\":\"Cliente\"}}").chat(payload));
        assertEquals(503, error.statusCode());
    }

    private Map<String, Object> tool(String name, Map<String, Object> properties) {
        return Map.of("type", "function", "function", Map.of("name", name, "parameters", Map.of(
                "type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false)));
    }

    @Test void visionUsesPrivateImageAttachmentsWithoutBase64InThePromptAndNormalizesSchemaOptionals() throws Exception {
        String data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6RyoAAAAASUVORK5CYII=";
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "Lee el diagrama"),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + data)))))));
        payload.put("response_format", Map.of("type", "json_object", "schema", json.readTree("""
                {"type":"object","additionalProperties":false,"required":["summary"],"properties":{"summary":{"type":"string"},"confidence":{"type":"number"}}}
                """)));
        Path home = Files.createDirectories(temporary.resolve("private-codex"));
        AtomicReference<Path> imagePath = new AtomicReference<>();
        var codecClient = new CodexCompletionClient(json, "codex", home.toString(), "gpt-6.1-sol", 5000,
                (arguments, directory, variables) -> {
                    Path image = Path.of(argument(arguments, "--image"));
                    imagePath.set(image);
                    assertTrue(image.startsWith(directory));
                    assertArrayEquals(Base64.getDecoder().decode(data), Files.readAllBytes(image));
                    command.set(List.copyOf(arguments));
                    schema.set(json.readTree(Files.readString(Path.of(argument(arguments, "--output-schema")))));
                    job.set(directory);
                    return startHarness(arguments, directory, variables, "{\"summary\":\"Pedido\",\"confidence\":null}",
                            "{\"type\":\"turn.completed\"}\n", 0, 0);
                });
        JsonNode envelope = codecClient.chat(payload);
        JsonNode value = json.readTree(envelope.at("/choices/0/message/content").asString());
        assertEquals("Pedido", value.get("summary").asString());
        assertFalse(value.has("confidence"));
        assertFalse(receivedPrompt.get().contains(data));
        assertFalse(receivedPrompt.get().contains("data:image"));
        assertTrue(receivedPrompt.get().contains("Lee el diagrama"));
        assertEquals(2, schema.get().get("required").size());
        assertFalse(Files.exists(imagePath.get()));
    }

    private Process startHarness(List<String> arguments, Path directory, Map<String, String> variables,
                                 String output, String events, int exit, long delay) throws IOException {
        // Fixtures are files because Windows' native argv handling removes JSON quotes inside argv values.
        Path outputFixture = Files.writeString(directory.resolve("fixture-output.txt"), output);
        Path eventsFixture = Files.writeString(directory.resolve("fixture-events.txt"), events);
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp", harnessClasspath(), Harness.class.getName(),
                argument(arguments, "--output-last-message"), outputFixture.toString(), eventsFixture.toString(), String.valueOf(exit), String.valueOf(delay));
        builder.directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(variables);
        Process child = builder.start();
        process.set(child);
        return new PromptCapturingProcess(child, receivedPrompt);
    }

    @Test void rejectsExternalImagesInsteadOfDownloadingThem() throws Exception {
        var client = client("{\"content\":\"OK\"}");
        var error = assertThrows(CodexApiException.class, () -> client.chat(Map.of("messages", List.of(Map.of("role", "user", "content",
                List.of(Map.of("type", "image_url", "image_url", Map.of("url", "https://untrusted.invalid/a.png"))))))));
        assertEquals(503, error.statusCode());
        assertNull(command.get());
    }

    @Test void malformedDuplicateTrailingAndMissingRequiredJsonFailWithoutRepair() throws Exception {
        for (String output : List.of("```json\n{\"content\":\"OK\"}\n```", "{\"content\":\"OK\"", "{\"content\":\"OK\"}{}",
                "{\"content\":\"first\",\"content\":\"last\"}", "{\"content\":null}", "{}")) {
            var error = assertThrows(CodexApiException.class, () -> client(output).chat(textPayload("Hola")), output);
            assertEquals(503, error.statusCode());
            assertFalse(Files.exists(job.get()));
        }
    }

    @Test void requiresCompletedTurnAndRejectsFailureEventsEvenWhenExitIsZero() throws Exception {
        for (String events : List.of("{\"type\":\"turn.started\"}\n", "{\"type\":\"error\",\"message\":\"private details\"}\n{\"type\":\"turn.completed\"}\n",
                "{\"type\":\"turn.completed\"}\n{\"type\":\"turn.failed\",\"error\":{\"message\":\"private details\"}}\n")) {
            var error = assertThrows(CodexApiException.class, () -> client("{\"content\":\"partial\"}", events, 0, 0, 3000).chat(textPayload("Hola")));
            assertEquals(503, error.statusCode());
            assertFalse(error.getMessage().contains("private"));
        }
    }

    @Test void preservesQuotaRetryWithoutLeakingProviderText() throws Exception {
        var error = assertThrows(CodexApiException.class, () -> client("{\"content\":\"partial\"}",
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"429 rate_limit private-token\",\"retry_after_seconds\":17}}\n", 1, 0, 3000).chat(textPayload("Hola")));
        assertEquals(429, error.statusCode());
        assertEquals(17, error.retryAfterSeconds());
        assertFalse(error.getMessage().contains("private-token"));
    }

    @Test void doesNotInventAQuotaResetTimeWhenTheProviderDoesNotReturnOne() throws Exception {
        var error = assertThrows(CodexApiException.class, () -> client("{\"content\":\"partial\"}",
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"usage limit reached\"}}\n", 1, 0, 3000)
                .chat(textPayload("Hola")));
        assertEquals(429, error.statusCode());
        assertEquals(0, error.retryAfterSeconds());
    }

    @Test void nonZeroExitNeverReturnsAnOtherwiseCompleteProposal() throws Exception {
        var error = assertThrows(CodexApiException.class, () -> client("{\"content\":\"partial\"}", "{\"type\":\"turn.completed\"}\n", 7, 0, 3000).chat(textPayload("Hola")));
        assertEquals(503, error.statusCode());
    }

    @Test void timeoutTerminatesItsProcessAndDeletesItsDirectory() throws Exception {
        long start = System.nanoTime();
        var error = assertThrows(CodexApiException.class, () -> client("{\"content\":\"late\"}", "{\"type\":\"turn.completed\"}\n", 0, 30000, 250).chat(textPayload("Hola")));
        assertEquals(503, error.statusCode());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
        assertFalse(process.get().isAlive());
        assertFalse(Files.exists(job.get()));
    }

    @Test void rejectsConcurrentRequestsWithRetryInsteadOfStartingAnotherProcess() throws Exception {
        var client = client("{\"content\":\"OK\"}", "{\"type\":\"turn.completed\"}\n", 0, 650, 5000);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> client.chat(textPayload("Primero")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (process.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
            assertNotNull(process.get());
            var error = assertThrows(CodexApiException.class, () -> client.chat(textPayload("Segundo")));
            assertEquals(429, error.statusCode());
            assertTrue(error.retryAfterSeconds() > 0);
            assertEquals("OK", first.get(3, TimeUnit.SECONDS).at("/choices/0/message/content").asString());
        }
    }

    @Test void excessiveEventOutputAndFinalOutputFailClosed() throws Exception {
        for (String kind : List.of("flood-events", "flood-result", "flood-stderr")) {
            var error = assertThrows(CodexApiException.class, () -> client(kind, "{\"type\":\"turn.completed\"}\n", 0, 0, 3000).chat(textPayload("Hola")));
            assertEquals(503, error.statusCode());
            assertFalse(Files.exists(job.get()));
        }
    }

    @Test void nonFatalItemWarningDoesNotInvalidateACompletedTurnButToolItemsDo() throws Exception {
        String warning = "{\"type\":\"item.completed\",\"item\":{\"id\":\"warning\",\"type\":\"error\",\"message\":\"unstable feature warning\"}}\n";
        String completed = "{\"type\":\"turn.completed\"}\n";
        assertEquals("OK", client("{\"content\":\"OK\"}", warning + completed, 0, 0, 3000)
                .chat(textPayload("Hola")).at("/choices/0/message/content").asString());
        for (String type : List.of("command_execution", "mcp_tool_call", "unexpected_new_tool")) {
            String events = "{\"type\":\"item.started\",\"item\":{\"id\":\"tool\",\"type\":\"" + type + "\"}}\n" + completed;
            assertEquals(503, assertThrows(CodexApiException.class,
                    () -> client("{\"content\":\"unsafe\"}", events, 0, 0, 3000).chat(textPayload("Hola"))).statusCode());
        }
    }

    @Test void localProbeChecksChatGptAuthenticationOnceWithoutSubmittingAnInference() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("private-codex"));
        AtomicInteger starts = new AtomicInteger();
        var client = new CodexCompletionClient(json, "codex", home.toString(), "gpt-6.1-sol", 3000,
                (arguments, directory, variables) -> {
                    assertTrue(arguments.contains("login"));
                    assertTrue(arguments.contains("status"));
                    assertFalse(arguments.contains("exec"));
                    assertFalse(arguments.contains("--output-schema"));
                    assertFalse(variables.containsKey("OPENAI_API_KEY"));
                    starts.incrementAndGet();
                    job.set(directory);
                    return startHarness(arguments, directory, variables, "unused", "Logged in using ChatGPT", 0, 0);
                });
        var first = client.probe("text");
        var second = client.probe("vision");
        assertTrue(first.available());
        assertEquals("READY", first.state());
        assertEquals("vision", second.name());
        assertEquals(1, starts.get(), "Health requests share a 60-second local probe cache");
        assertFalse(Files.exists(job.get()));
    }

    @Test void missingPrivateHomeFailsSafelyBeforeStartingAProcess() {
        var client = new CodexCompletionClient(json, "codex", "", "gpt-6.1-sol", 3000,
                (arguments, directory, variables) -> { fail("No process may start without a dedicated home"); return null; });
        assertFalse(client.probe("text").available());
        assertEquals(503, assertThrows(CodexApiException.class, () -> client.chat(textPayload("Hola"))).statusCode());
    }

    @Test void timeoutAlsoCoversAChildThatNeverReadsStdin() throws Exception {
        var error = assertThrows(CodexApiException.class, () -> client("no-read", "", 0, 0, 350).chat(textPayload("x".repeat(600 * 1024))));
        assertEquals(503, error.statusCode());
        assertFalse(process.get().isAlive());
        assertFalse(Files.exists(job.get()));
    }

    @Test void interruptionRetainsCancellationAndCleansUpTheOwnedProcess() throws Exception {
        var client = client("{\"content\":\"late\"}", "{\"type\":\"turn.completed\"}\n", 0, 30000, 5000);
        AtomicReference<CodexApiException> error = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread worker = Thread.ofVirtual().start(() -> {
            try { client.chat(textPayload("Hola")); }
            catch (CodexApiException failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (process.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        assertNotNull(process.get());
        worker.interrupt();
        worker.join(2000);
        assertFalse(worker.isAlive());
        assertNotNull(error.get());
        assertEquals(503, error.get().statusCode());
        assertTrue(interrupted.get());
        assertFalse(process.get().isAlive());
        assertFalse(Files.exists(job.get()));
    }

    @Test void timeoutKillsOnlyItsOwnDescendantsAndLeavesUnrelatedProcessesAlive() throws Exception {
        Path childPid = temporary.resolve("owned-child.pid");
        Process unrelated = new ProcessBuilder(javaExecutable(), "-cp", harnessClasspath(), Harness.class.getName(), "sleep").start();
        try {
            var error = assertThrows(CodexApiException.class, () -> client("spawn-child:" + childPid, "", 0, 30000, 1500).chat(textPayload("Hola")));
            assertEquals(503, error.statusCode());
            assertTrue(Files.exists(childPid), "The test must observe a real descendant before the timeout");
            long pid = Long.parseLong(Files.readString(childPid));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(unrelated.isAlive());
            assertFalse(Files.exists(job.get()));
        } finally { unrelated.destroyForcibly(); unrelated.waitFor(1, TimeUnit.SECONDS); }
    }

    @Test void requestDirectoryIsPrivateBeforeTheChildStarts() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("private-codex"));
        var client = new CodexCompletionClient(json, "codex", home.toString(), "gpt-6.1-sol", 3000,
                (arguments, directory, variables) -> {
                    if (Files.getFileAttributeView(directory, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                        assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(directory));
                    } else {
                        var acl = Files.getFileAttributeView(directory, java.nio.file.attribute.AclFileAttributeView.class);
                        assertNotNull(acl);
                        assertFalse(acl.getAcl().isEmpty());
                        for (var entry : acl.getAcl()) assertEquals(acl.getOwner(), entry.principal());
                    }
                    return startHarness(arguments, directory, variables, "{\"content\":\"OK\"}", "{\"type\":\"turn.completed\"}\n", 0, 0);
                });
        assertEquals("OK", client.chat(textPayload("Hola")).at("/choices/0/message/content").asString());
    }

    @Test void acceptsAnExistingValidPngLargerThanFourMiBWithoutLoweringTheImageContract() throws Exception {
        byte[] png = noisyImage(1280, "png");
        assertTrue(png.length > 4 * 1024 * 1024);
        assertTrue(png.length < 10 * 1024 * 1024);
        var input = new AssistantImageInputValidator(10 * 1024 * 1024, 64, 8192)
                .validate(new MockMultipartFile("image", "large.png", "image/png", png));
        assertEquals(1280, input.width());
        assertExtractedImage(png, "image/png");
    }

    @Test void acceptsAJpegWhoseExistingRgbNormalizationExpandsBeyondTheOriginalUploadLimit() throws Exception {
        byte[] jpeg = noisyImage(2048, "jpg");
        assertTrue(jpeg.length < 10 * 1024 * 1024);
        var input = new AssistantImageInputValidator(10 * 1024 * 1024, 64, 8192)
                .validate(new MockMultipartFile("image", "diagram.jpg", "image/jpeg", jpeg));
        var normalized = new VisionImageNormalizer().normalize(input);
        assertTrue(normalized.bytes().length > 10 * 1024 * 1024, "A valid JPEG can expand past 10 MiB when normalized to PNG");
        assertExtractedImage(normalized.bytes(), normalized.mediaType());
    }

    private byte[] noisyImage(int dimension, String format) throws IOException {
        BufferedImage image = new BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_RGB);
        java.util.Random noise = new java.util.Random(47123);
        int[] pixels = new int[dimension];
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < dimension; column++) pixels[column] = noise.nextInt(0x1000000);
            image.setRGB(0, row, dimension, 1, pixels, 0, dimension);
        }
        try (var bytes = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, format, bytes));
            return bytes.toByteArray();
        }
    }

    private void assertExtractedImage(byte[] image, String mediaType) throws Exception {
        Path directory = CodexFiles.directory();
        try {
            var request = new CodexRequestCodec(json).prepare(Map.of("messages", List.of(Map.of("role", "user", "content",
                    List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(image))))))), directory);
            assertEquals(1, request.images().size());
            assertEquals(image.length, Files.size(request.images().getFirst()));
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] expectedHash = digest.digest(image);
            digest.reset();
            try (var decoded = new java.security.DigestInputStream(Files.newInputStream(request.images().getFirst()), digest)) {
                decoded.transferTo(java.io.OutputStream.nullOutputStream());
            }
            assertArrayEquals(expectedHash, digest.digest());
            assertFalse(request.prompt().contains("data:image"));
        } finally { CodexFiles.cleanup(directory); }
    }

    static String argument(List<String> arguments, String key) {
        int index = arguments.indexOf(key);
        return index < 0 ? "" : arguments.get(index + 1);
    }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    static String harnessClasspath() {
        try { return Path.of(Harness.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    /** Real child process, without a model, credentials, shell script, or external dependency. */
    public static class Harness {
        public static void main(String[] arguments) throws Exception {
            if ("sleep".equals(arguments[0])) { Thread.sleep(30000); return; }
            String output = Files.readString(Path.of(arguments[1]));
            String events = Files.readString(Path.of(arguments[2]));
            if ("no-read".equals(output)) { Thread.sleep(30000); return; }
            byte[] stdin = System.in.readAllBytes();
            Files.write(Path.of("received-prompt.txt"), stdin);
            if (output.startsWith("spawn-child:")) {
                String javaBinary = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
                Process descendant = new ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"), Harness.class.getName(), "sleep").start();
                Files.writeString(Path.of(output.substring("spawn-child:".length())), String.valueOf(descendant.pid()));
            }
            if ("flood-events".equals(output)) { System.out.print("x".repeat(2 * 1024 * 1024)); System.out.flush(); }
            if ("flood-stderr".equals(output)) { System.err.print("x".repeat(256 * 1024)); System.err.flush(); }
            Thread.sleep(Long.parseLong(arguments[4]));
            if (!arguments[0].isEmpty()) Files.writeString(Path.of(arguments[0]), "flood-result".equals(output) ? "x".repeat(3 * 1024 * 1024) : output);
            System.out.print(events);
            System.exit(Integer.parseInt(arguments[3]));
        }
    }

    /** Captures the actual bytes sent over stdin before request cleanup. */
    private static class PromptCapturingProcess extends Process {
        private final Process delegate;
        private final AtomicReference<String> prompt;
        private final java.io.ByteArrayOutputStream input = new java.io.ByteArrayOutputStream();
        PromptCapturingProcess(Process delegate, AtomicReference<String> prompt) { this.delegate = delegate; this.prompt = prompt; }
        @Override public java.io.OutputStream getOutputStream() {
            return new java.io.FilterOutputStream(delegate.getOutputStream()) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); input.write(bytes, offset, length); }
                @Override public void write(int value) throws IOException { out.write(value); input.write(value); }
                @Override public void close() throws IOException { prompt.set(input.toString(StandardCharsets.UTF_8)); super.close(); }
            };
        }
        @Override public java.io.InputStream getInputStream() { return delegate.getInputStream(); }
        @Override public java.io.InputStream getErrorStream() { return delegate.getErrorStream(); }
        @Override public int waitFor() throws InterruptedException { return delegate.waitFor(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return delegate.waitFor(timeout, unit); }
        @Override public int exitValue() { return delegate.exitValue(); }
        @Override public void destroy() { delegate.destroy(); }
        @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
        @Override public boolean isAlive() { return delegate.isAlive(); }
        @Override public ProcessHandle toHandle() { return delegate.toHandle(); }
        @Override public long pid() { return delegate.pid(); }
    }
}
