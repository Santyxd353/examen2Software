package com.classforge.projectassistant.llm;

import com.classforge.assistant.codex.CodexCompletionClient;
import com.classforge.projectassistant.AssistantConversationResponse;
import com.classforge.projectassistant.AssistantMode;
import com.classforge.projectassistant.ProjectAssistantContext;
import com.classforge.projectassistant.ProjectAssistantConversationGateway;
import com.classforge.projectassistant.ProjectAssistantUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/** Read-only conversation and grounded REVIEW findings using the existing project snapshot contract. */
@Component
@ConditionalOnProperty(name = "classforge.assistant.text.provider", havingValue = "codex")
public class CodexProjectAssistantGateway implements ProjectAssistantConversationGateway {
    private final CodexCompletionClient client;
    private final String model;
    private final int maxTokens;
    private final ProjectAssistantConversationSupport support;

    public CodexProjectAssistantGateway(
            CodexCompletionClient client,
            JsonMapper mapper,
            @Value("${classforge.assistant.codex.model:gpt-6.1-sol}") String model,
            @Value("${classforge.assistant.conversation.max-completion-tokens:2048}") int maxTokens,
            @Value("${classforge.assistant.conversation.max-context-chars:18000}") int maxContextChars
    ) {
        if (model == null || model.isBlank() || maxTokens <= 0) {
            throw new IllegalArgumentException("Modelo de IA y tokens deben ser válidos.");
        }
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.support = new ProjectAssistantConversationSupport(mapper, maxContextChars);
    }

    @Override
    public AssistantConversationResponse respond(ProjectAssistantContext context, String userMessage, AssistantMode mode) {
        var conversation = support.conversation(context, userMessage, mode);
        Map<String, Object> payload = Map.of("model", model, "temperature", 0.2d, "stream", false,
                "max_completion_tokens", maxTokens, "messages", conversation);
        // Provider errors remain actionable and retain authentication/quota status without retries.
        JsonNode envelope = client.chat(payload);
        JsonNode choices = envelope == null ? null : envelope.get("choices");
        JsonNode choice = choices != null && choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
        JsonNode finish = choice == null ? null : choice.get("finish_reason");
        if (finish != null && finish.isString() && "length".equals(finish.asString())) {
            throw new ProjectAssistantUnavailableException(
                    "El proveedor de IA devolvió una respuesta incompleta por límite de tokens. Puedes reintentar con una consulta más breve.");
        }
        JsonNode message = choice == null ? null : choice.get("message");
        JsonNode content = message == null ? null : message.get("content");
        return support.response(content != null && content.isString() ? content.asString() : null, context, mode);
    }
}
