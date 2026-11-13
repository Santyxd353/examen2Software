package com.classforge.assistant;

import com.classforge.assistant.codex.CodexApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssistantProviderErrorResponsesTests {
    @Test
    void codexAuthenticationFailureDoesNotReturnAnApplicationLoginError() {
        var response = AssistantProviderErrorResponses.from(new CodexApiException(401, 0, "Inicia sesión en Codex."));
        assertNotNull(response);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("CODEX_UNAVAILABLE", response.getBody().get("error"));
    }

    @Test
    void preservesWrappedCodexQuotaAndRetryAfter() {
        var response = AssistantProviderErrorResponses.from(new RuntimeException("wrapper",
                new CodexApiException(429, 17, "Codex alcanzó un límite.")));
        assertNotNull(response);
        assertEquals(429, response.getStatusCode().value());
        assertEquals("17", response.getHeaders().getFirst("Retry-After"));
        assertEquals("CODEX_RATE_LIMIT", response.getBody().get("error"));
    }

    @Test
    void unrelatedAndCyclicCausesDoNotBecomeProviderErrors() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertNull(AssistantProviderErrorResponses.from(first));
        assertNull(AssistantProviderErrorResponses.from(new RuntimeException("other")));
    }
}
