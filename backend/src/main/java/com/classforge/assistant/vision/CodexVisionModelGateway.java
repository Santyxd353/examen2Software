package com.classforge.assistant.vision;

import com.classforge.assistant.codex.CodexCompletionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Retains proposal parsing, grounding and optional closed relationship passes with Codex transport. */
@Component
@ConditionalOnProperty(name = "classforge.assistant.vision.provider", havingValue = "codex")
public class CodexVisionModelGateway extends LlamaCppVisionModelGateway {
    private final CodexCompletionClient client;

    public CodexVisionModelGateway(
            JsonMapper jsonMapper,
            VisionPromptBuilder promptBuilder,
            CodexCompletionClient client,
            @Value("${classforge.assistant.codex.model:gpt-6.1-sol}") String model,
            @Value("${classforge.assistant.vision.max-completion-tokens:3200}") int maxCompletionTokens,
            @Value("${classforge.assistant.vision.dense-two-pass-enabled:false}") boolean denseTwoPassEnabled,
            @Value("${classforge.assistant.vision.dense-two-pass-min-classes:4}") int denseTwoPassMinClasses,
            @Value("${classforge.assistant.vision.relationship-max-completion-tokens:1800}") int relationshipMaxCompletionTokens
    ) {
        // The overridden transport uses Codex's process, model session and timeout exclusively.
        super(jsonMapper, promptBuilder, "http://unused.invalid", model, 1,
                maxCompletionTokens, denseTwoPassEnabled, denseTwoPassMinClasses, relationshipMaxCompletionTokens);
        this.client = client;
    }

    @Override
    protected JsonNode requestCompletion(Map<String, Object> payload, String passName) {
        return OpenAiVisionCompletion.request(client, payload);
    }
}
