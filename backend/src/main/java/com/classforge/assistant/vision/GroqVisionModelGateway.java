package com.classforge.assistant.vision;

import com.classforge.assistant.groq.GroqClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Uses Groq transport while retaining the validated semantic and closed two-pass pipeline. */
@Component
@ConditionalOnProperty(name = "classforge.assistant.vision.provider", havingValue = "groq")
public class GroqVisionModelGateway extends LlamaCppVisionModelGateway {
    private final GroqClient client;

    public GroqVisionModelGateway(
            JsonMapper jsonMapper,
            VisionPromptBuilder promptBuilder,
            GroqClient client,
            @Value("${classforge.assistant.groq.vision-model:qwen/qwen3.8-27b}") String model,
            @Value("${classforge.assistant.vision.max-completion-tokens:3200}") int maxCompletionTokens,
            @Value("${classforge.assistant.vision.dense-two-pass-enabled:false}") boolean denseTwoPassEnabled,
            @Value("${classforge.assistant.vision.dense-two-pass-min-classes:4}") int denseTwoPassMinClasses,
            @Value("${classforge.assistant.vision.relationship-max-completion-tokens:1800}") int relationshipMaxCompletionTokens
    ) {
        // The overridden transport exclusively uses GroqClient's URL, authentication and timeout.
        super(jsonMapper, promptBuilder, "http://unused.invalid", model, 1,
                maxCompletionTokens, denseTwoPassEnabled, denseTwoPassMinClasses, relationshipMaxCompletionTokens);
        this.client = client;
    }

    @Override
    protected JsonNode requestCompletion(Map<String, Object> payload, String passName) {
        return OpenAiVisionCompletion.request(client, payload);
    }
}
