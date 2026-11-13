package com.classforge.assistant.vision;

import com.classforge.assistant.codex.CodexCompletionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Retains class mapping, closed edge and multiplicity validation from the OpenCV pipeline. */
@Component
@ConditionalOnProperty(name = "classforge.assistant.vision.provider", havingValue = "codex")
public class CodexVisionHybridModelGateway extends LlamaCppVisionHybridModelGateway {
    private final CodexCompletionClient client;

    public CodexVisionHybridModelGateway(
            JsonMapper jsonMapper,
            VisionHybridPromptBuilder promptBuilder,
            CodexCompletionClient client,
            @Value("${classforge.assistant.codex.model:gpt-6.1-sol}") String model,
            @Value("${classforge.assistant.vision.dense-hybrid.localization-max-completion-tokens:1200}") int localizationTokens,
            @Value("${classforge.assistant.vision.dense-hybrid.relationship-max-completion-tokens:512}") int relationshipTokens,
            @Value("${classforge.assistant.vision.dense-hybrid.multiplicity-max-completion-tokens:128}") int multiplicityTokens
    ) {
        super(jsonMapper, promptBuilder, "http://unused.invalid", model, 1,
                localizationTokens, relationshipTokens, multiplicityTokens);
        this.client = client;
    }

    @Override
    protected JsonNode requestCompletion(Map<String, Object> payload, String stage) {
        return OpenAiVisionCompletion.request(client, payload);
    }
}
