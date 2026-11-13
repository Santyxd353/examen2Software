package com.classforge.assistant.tools;

import com.classforge.assistant.codex.CodexCompletionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Uses Codex transport to propose one validated UML tool call per preview step. */
@Component
@ConditionalOnProperty(name = "classforge.assistant.text.provider", havingValue = "codex")
public class CodexToolCallingGateway extends GroqToolCallingGateway {
    public CodexToolCallingGateway(
            CodexCompletionClient client,
            JsonMapper mapper,
            @Value("${classforge.assistant.codex.model:gpt-6.1-sol}") String model,
            @Value("${classforge.assistant.codex.tool-max-completion-tokens:2048}") int maxTokens,
            @Value("${classforge.assistant.conversation.max-context-chars:18000}") int maxContextChars
    ) {
        super(client, mapper, model, maxTokens, maxContextChars);
    }
}
