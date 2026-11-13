package com.classforge.assistant.vision;

import com.classforge.assistant.llm.AssistantChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapts the validated vision pipeline to an OpenAI-compatible provider transport. */
final class OpenAiVisionCompletion {
    private static final List<String> COORDINATES = List.of("x", "y", "width", "height");
    private static final String SEMANTIC_EVIDENCE_INSTRUCTION = """

            En esta transcripcion semantica, evidence contiene solo label y confidence opcional.
            No incluyas x, y, width ni height en evidence; no generes bounding boxes ni coordenadas.
            La geometria se obtiene por separado con OpenCV. Omite cualquier metadato opcional incierto.
            """;

    private OpenAiVisionCompletion() {
    }

    static JsonNode request(AssistantChatClient client, Map<String, Object> pipelinePayload) {
        Map<String, Object> payload = new LinkedHashMap<>(pipelinePayload);
        payload.put("max_completion_tokens", payload.remove("max_tokens"));
        payload.remove("chat_template_kwargs");
        Map<?, ?> pipelineFormat = (Map<?, ?>) payload.get("response_format");
        JsonNode schema = (JsonNode) pipelineFormat.get("schema");
        JsonNode title = schema.get("title");
        if (title != null && ("VisionUmlProposal".equals(title.asString())
                || "VisionRelationshipPassProposal".equals(title.asString()))) {
            schema = schema.deepCopy();
            removeOptionalEvidenceCoordinates(schema);
            payload.put("messages", semanticMessages((List<?>) payload.get("messages")));
        }
        payload.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "vision_proposal",
                        "strict", false,
                        "schema", schema
                )
        ));
        return client.chat(payload);
    }

    private static void removeOptionalEvidenceCoordinates(JsonNode node) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            JsonNode evidence = properties == null ? null : properties.get("evidence");
            if (evidence != null && evidence.isObject()) {
                JsonNode evidenceProperties = evidence.get("properties");
                JsonNode required = evidence.get("required");
                boolean coordinatesRequired = false;
                if (required != null && required.isArray()) {
                    for (JsonNode field : required) {
                        coordinatesRequired |= COORDINATES.contains(field.asString());
                    }
                }
                if (!coordinatesRequired && evidenceProperties instanceof ObjectNode fields && fields.has("label")) {
                    for (String coordinate : COORDINATES) fields.remove(coordinate);
                }
            }
            for (var property : node.properties()) removeOptionalEvidenceCoordinates(property.getValue());
        } else if (node.isArray()) {
            for (JsonNode child : node) removeOptionalEvidenceCoordinates(child);
        }
    }

    private static List<Map<String, Object>> semanticMessages(List<?> messages) {
        List<Map<String, Object>> adapted = new ArrayList<>();
        for (Object value : messages) {
            Map<?, ?> message = (Map<?, ?>) value;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var field : message.entrySet()) copy.put((String) field.getKey(), field.getValue());
            if ("system".equals(message.get("role"))) {
                copy.put("content", message.get("content") + SEMANTIC_EVIDENCE_INSTRUCTION);
            }
            adapted.add(copy);
        }
        return adapted;
    }
}
