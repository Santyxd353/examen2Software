package com.classforge.assistant.codex;

import com.classforge.assistant.AssistantRuntimeStatus;
import com.classforge.assistant.llm.AssistantChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Server-only Codex CLI 0.167.2 transport, using an operator-provisioned private ChatGPT home. */
@Component
public class CodexCompletionClient implements AssistantChatClient {
    interface ProcessLauncher { Process start(List<String> command, Path directory, Map<String, String> environment) throws IOException; }

    private static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    private static final List<String> DISABLED_FEATURES = List.of("shell_tool", "unified_exec", "shell_snapshot", "apps", "plugins",
            "remote_plugin", "skill_search", "skill_mcp_dependency_install", "hooks", "code_mode", "code_mode_host", "computer_use",
            "browser_use", "browser_use_external", "in_app_browser", "view_image", "image_generation", "multi_agent", "multi_agent_v2",
            "sleep_tool", "tool_suggest", "workspace_dependencies", "goals", "memories");
    private static final Pattern QUOTA = Pattern.compile("(?i)(\\b429\\b|rate[_ -]?limit|usage[_ -]?limit|quota|insufficient_quota|too many requests|capacity|server[_ -]?busy)");
    private static final Pattern RETRY = Pattern.compile("(?i)retry[_ -]?after(?:[_ -]?seconds)?[\\s\"':=]+(\\d{1,6})");

    private final String executable;
    private final Path home;
    private final String model;
    private final long timeoutMillis;
    private final CodexRequestCodec codec;
    private final JsonMapper json;
    private final CodexProcessRunner processes;
    private final Semaphore inFlight = new Semaphore(1);
    private volatile Probe cachedProbe;

    private record Probe(boolean available, String state, String message, long validUntil) {}

    @Autowired
    public CodexCompletionClient(JsonMapper json,
            @Value("${classforge.assistant.codex.executable:codex}") String executable,
            @Value("${classforge.assistant.codex.home:}") String home,
            @Value("${classforge.assistant.codex.model:gpt-6.1-sol}") String model,
            @Value("${classforge.assistant.codex.request-timeout-millis:90000}") long timeoutMillis) {
        this(json, executable, home, model, timeoutMillis, (command, directory, environment) -> {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            return builder.start();
        });
    }

    CodexCompletionClient(JsonMapper json, String executable, String home, String model, long timeoutMillis, ProcessLauncher launcher) {
        if (json == null || executable == null || executable.isBlank() || model == null || model.isBlank()
                || timeoutMillis <= 0 || timeoutMillis > 600000) throw new IllegalArgumentException("La configuración y el plazo de Codex deben ser válidos.");
        this.json = json;
        this.executable = executable.trim();
        this.home = home == null || home.isBlank() ? null : Path.of(home).toAbsolutePath().normalize();
        this.model = model.trim();
        this.timeoutMillis = timeoutMillis;
        this.codec = new CodexRequestCodec(json);
        this.processes = new CodexProcessRunner(launcher);
    }

    @Override public JsonNode chat(Map<String, Object> payload) {
        if (!inFlight.tryAcquire()) throw new CodexApiException(429, 2, "Codex está atendiendo otra solicitud. Espera antes de reintentar.");
        Path job = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            requireConfiguredHome();
            job = CodexFiles.directory();
            var request = codec.prepare(payload, job);
            Path schema = CodexFiles.write(job.resolve("schema.json"), json.writeValueAsString(request.strictSchema()));
            Path instructions = CodexFiles.write(job.resolve("model-instructions.txt"), CodexRequestCodec.MODEL_INSTRUCTIONS);
            Path result = job.resolve("result.json");
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.add("exec");
            command.addAll(List.of("--ignore-user-config", "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only", "--json", "--color", "never",
                    "--model", model, "--cd", job.toString(), "--output-schema", schema.toString(), "--output-last-message", result.toString()));
            command.addAll(overrides());
            // CLI accepts a raw string when TOML parsing fails. This also avoids Windows argv quote removal.
            addConfig(command, "model_instructions_file=" + instructions);
            for (Path image : request.images()) command.addAll(List.of("--image", image.toString()));
            command.add("-");
            var execution = processes.run(command, job, environment(job), request.prompt(), deadline);
            validateEvents(execution);
            if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) > MAX_RESULT_BYTES) throw unavailable();
            byte[] output;
            try (var stream = Files.newInputStream(result)) { output = stream.readNBytes(MAX_RESULT_BYTES + 1); }
            if (output.length == 0 || output.length > MAX_RESULT_BYTES || System.nanoTime() >= deadline) throw unavailable();
            JsonNode response = codec.envelope(new String(output, StandardCharsets.UTF_8), request);
            if (System.nanoTime() >= deadline) throw unavailable();
            return response;
        } catch (CodexApiException safe) { throw safe; }
        catch (Exception failure) { throw unavailable(); }
        finally { CodexFiles.cleanup(job); inFlight.release(); }
    }

    /** Cached local authentication check; it never submits an inference or reads credentials in Java. */
    public AssistantRuntimeStatus probe(String component) {
        long start = System.nanoTime();
        Probe current = cachedProbe;
        if (current != null && current.validUntil() > start) return status(component, current, start);
        if (!inFlight.tryAcquire()) return new AssistantRuntimeStatus(component, true, "BUSY", 0, "Codex está atendiendo una solicitud.");
        Path job = null;
        try {
            current = cachedProbe;
            if (current != null && current.validUntil() > System.nanoTime()) return status(component, current, start);
            requireConfiguredHome();
            job = CodexFiles.directory();
            List<String> command = new ArrayList<>();
            command.add(executable);
            command.addAll(overrides());
            command.addAll(List.of("login", "status"));
            var result = processes.run(command, job, environment(job), "", start + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 5000)));
            String output = result.stdout() + "\n" + result.stderr();
            boolean ready = result.exitCode() == 0 && output.contains("Logged in using ChatGPT");
            current = new Probe(ready, ready ? "READY" : "AUTH_REQUIRED",
                    ready ? "La sesión privada ChatGPT de Codex está disponible." : "La sesión privada ChatGPT de Codex requiere autenticación.",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
        } catch (Exception failure) {
            current = new Probe(false, "UNAVAILABLE", "Codex no está disponible. Revisa el ejecutable y la sesión privada del backend.",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
        } finally { CodexFiles.cleanup(job); inFlight.release(); }
        cachedProbe = current;
        return status(component, current, start);
    }

    private AssistantRuntimeStatus status(String component, Probe probe, long start) {
        return new AssistantRuntimeStatus(component, probe.available(), probe.state(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), probe.message());
    }

    private void requireConfiguredHome() {
        if (home == null || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new CodexApiException(503, 0, "Configura el home privado de Codex y su sesión ChatGPT en el backend.");
        }
    }

    private List<String> overrides() {
        List<String> arguments = new ArrayList<>();
        for (String option : List.of("forced_login_method=chatgpt", "cli_auth_credentials_store=file", "model_provider=openai", "web_search=disabled", "mcp_servers={}",
                "apps._default.enabled=false", "approval_policy=never", "project_doc_max_bytes=0", "shell_environment_policy.inherit=none",
                "shell_environment_policy.include_only=[]", "tools.update_plan.enabled=false", "tools.experimental_request_user_input.enabled=false",
                "suppress_unstable_features_warning=true", "features.skip_host_skill_discovery=true")) addConfig(arguments, option);
        for (String feature : DISABLED_FEATURES) addConfig(arguments, "features." + feature + "=false");
        return arguments;
    }

    private void addConfig(List<String> command, String override) { command.addAll(List.of("-c", override)); }

    private Map<String, String> environment(Path job) throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        // Explicit allowlist: app/provider keys, proxy URLs, JVM options and global Codex settings cannot cross this boundary.
        String windows = System.getenv("SystemRoot");
        if (windows != null && !windows.isBlank()) {
            environment.put("SystemRoot", windows);
            environment.put("WINDIR", windows);
            environment.put("PATH", Path.of(windows, "System32") + ";" + windows);
        } else environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        Path userHome = Files.createDirectory(job.resolve("user-home"));
        environment.put("HOME", userHome.toString());
        environment.put("USERPROFILE", userHome.toString());
        environment.put("XDG_CONFIG_HOME", userHome.resolve("config").toString());
        environment.put("XDG_CACHE_HOME", userHome.resolve("cache").toString());
        environment.put("CODEX_HOME", home.toString());
        environment.put("TEMP", job.toString());
        environment.put("TMP", job.toString());
        environment.put("TMPDIR", job.toString());
        environment.put("NO_COLOR", "1");
        return environment;
    }

    private void validateEvents(CodexProcessRunner.Result result) {
        boolean completed = false;
        boolean failed = result.exitCode() != 0;
        int events = 0;
        for (String line : result.stdout().lines().toList()) {
            if (line.isBlank()) continue;
            if (++events > 8192) throw unavailable();
            JsonNode event = codec.parse(line);
            String type = event.path("type").asString("");
            if ("turn.completed".equals(type)) completed = true;
            if ("error".equals(type) || "turn.failed".equals(type)) failed = true;
            JsonNode item = event.get("item");
            if (item != null) {
                String itemType = item.path("type").asString("");
                // Fail closed if this CLI ever emits any new executable/tool item kind.
                if (!List.of("agent_message", "reasoning", "error").contains(itemType)) failed = true;
            }
        }
        if (failed || !completed) {
            String diagnostics = result.stdout() + "\n" + result.stderr();
            if (QUOTA.matcher(diagnostics).find()) {
                var retry = RETRY.matcher(diagnostics);
                long seconds = retry.find() ? Math.max(1, Math.min(86400, Long.parseLong(retry.group(1)))) : 0;
                throw new CodexApiException(429, seconds, "Se alcanzó la cuota o disponibilidad de Codex. Espera antes de reintentar la misma solicitud.");
            }
            throw unavailable();
        }
    }

    private CodexApiException unavailable() { return new CodexApiException(503, 0, "Codex no devolvió una respuesta completa y válida. Puedes reintentar."); }
}
