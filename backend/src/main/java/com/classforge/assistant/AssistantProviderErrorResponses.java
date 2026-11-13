package com.classforge.assistant;

import com.classforge.assistant.codex.CodexApiException;
import com.classforge.assistant.groq.GroqErrorResponses;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Provider authentication failures belong to the assistant, not the ClassForge login. */
public final class AssistantProviderErrorResponses {
    private AssistantProviderErrorResponses() { }

    public static ResponseEntity<Map<String, Object>> from(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof CodexApiException codex) {
                int status = codex.statusCode() == 429 ? 429 : 503;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", status == 429 ? "CODEX_RATE_LIMIT" : "CODEX_UNAVAILABLE");
                body.put("message", codex.getMessage());
                body.put("retryAfterSeconds", codex.retryAfterSeconds());
                var response = ResponseEntity.status(status);
                if (codex.retryAfterSeconds() > 0) {
                    response.header(HttpHeaders.RETRY_AFTER, Long.toString(codex.retryAfterSeconds()));
                }
                return response.body(body);
            }
        }
        return GroqErrorResponses.from(failure);
    }
}
