package com.classforge.generation.spring.generated;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class GeneratedTextValidator {
    private static final Pattern FREEMARKER_EXPRESSION = Pattern.compile("\\$\\{([^}\\r\\n]+)}");
    private static final Set<String> ALLOWED_RUNTIME_PLACEHOLDERS = Set.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET",
            "LLAMA_URL", "LLAMA_MODEL", "WHISPER_URL", "WHISPER_LANGUAGE", "APP_API_BASE_URL",
            "GROQ_API_KEY", "GROQ_BASE_URL", "GROQ_TEXT_MODEL", "GROQ_SPEECH_MODEL", "GROQ_LANGUAGE", "GROQ_REQUEST_TIMEOUT_MILLIS",
            "ASSISTANT_TEXT_PROVIDER", "ASSISTANT_VOICE_PROVIDER", "CODEX_EXECUTABLE", "CODEX_HOME", "CODEX_MODEL", "CODEX_REQUEST_TIMEOUT_MILLIS",
            "app.assistant.text.provider", "app.assistant.voice.provider", "app.assistant.codex.executable",
            "app.assistant.codex.home", "app.assistant.codex.model", "app.assistant.codex.request-timeout-millis",
            "app.assistant.groq.api-key", "app.assistant.groq.base-url", "app.assistant.groq.text-model",
            "app.assistant.groq.speech-model", "app.assistant.groq.language", "app.assistant.groq.request-timeout-millis",
            "app.security.jwt-secret", "app.security.jwt-expiration-seconds",
            "app.assistant.llama-url", "app.assistant.llama-model", "app.assistant.whisper-url",
            "app.assistant.whisper-language", "app.assistant.api-base-url");

    String validateText(
            GeneratedFile file,
            List<GeneratedProjectDiagnostic> diagnostics
    ) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.content()));
        } catch (CharacterCodingException exception) {
            add(diagnostics, GeneratedProjectDiagnosticCode.INVALID_TEXT_ENCODING, file.path(),
                    "Text content is not valid UTF-8.");
            return null;
        }
        byte[] content = file.content();
        for (byte value : content) {
            if (value == '\r') {
                add(diagnostics, GeneratedProjectDiagnosticCode.INVALID_LINE_ENDINGS, file.path(),
                        "Text content must use LF only.");
                break;
            }
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    void validateTemplateMarkers(
            String path,
            String text,
            List<GeneratedProjectDiagnostic> diagnostics
    ) {
        if (text.contains("<#") || text.contains("</#") || text.contains("<@")
                || text.contains("</@") || text.contains("[#") || text.contains("[/#")) {
            add(diagnostics, GeneratedProjectDiagnosticCode.UNRESOLVED_TEMPLATE_MARKER, path,
                    "Generated text still contains a FreeMarker directive.");
        }
        if (path.equals("gradlew") || path.equals("gradlew.bat")) {
            return;
        }
        // TypeScript uses ${...} for native template literals. Those expressions are
        // runtime JavaScript syntax, not leaked FreeMarker placeholders. Keep the
        // directive checks above, but do not reinterpret Angular source literals.
        if ((path.startsWith("frontend/") && path.endsWith(".ts")) || path.startsWith("mobile/")) {
            return;
        }
        Matcher matcher = FREEMARKER_EXPRESSION.matcher(text);
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String key = expression.contains(":")
                    ? expression.substring(0, expression.indexOf(':'))
                    : expression;
            if (!ALLOWED_RUNTIME_PLACEHOLDERS.contains(key)) {
                add(diagnostics, GeneratedProjectDiagnosticCode.UNRESOLVED_TEMPLATE_MARKER, path,
                        "Generated text still contains an unresolved template expression: ${" + expression + "}");
            }
        }
    }

    private void add(List<GeneratedProjectDiagnostic> diagnostics, GeneratedProjectDiagnosticCode code, String path, String message) {
        diagnostics.add(new GeneratedProjectDiagnostic(code, path, message));
    }
}
