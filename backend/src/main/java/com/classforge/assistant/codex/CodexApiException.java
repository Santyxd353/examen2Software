package com.classforge.assistant.codex;

import com.classforge.assistant.AssistantPlanningException;

/** Safe, application-owned error; provider output and credentials are never included. */
public class CodexApiException extends AssistantPlanningException {
    private final int statusCode;
    private final long retryAfterSeconds;

    public CodexApiException(int statusCode, long retryAfterSeconds, String message) {
        super(message);
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int statusCode() { return statusCode; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
