package com.classforge.assistant.vision;

import com.classforge.assistant.AssistantPlanningException;
import com.classforge.assistant.codex.CodexApiException;
import com.classforge.assistant.codex.CodexCompletionClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodexVisionModelGatewayTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CodexCompletionClient client = mock(CodexCompletionClient.class);
    private final AtomicReference<JsonNode> payload = new AtomicReference<>();

    @Test
    void sendsImageThroughSharedSemanticSchemaAndKeepsGrounding() {
        when(client.chat(any())).thenAnswer(invocation -> {
            payload.set(mapper.valueToTree(invocation.getArgument(0)));
            return completion(proposal(), "stop");
        });
        UUID projectId = UUID.randomUUID(), classId = UUID.randomUUID();

        var result = gateway(false).analyze(image(), new VisionProjectContext(projectId, 7L,
                List.of(new VisionExistingClassContext(classId, "Cliente", List.of("nombre")))));

        assertEquals(4, result.safeClasses().size());
        assertEquals("c2", result.safeRelationships().getFirst().targetRef());
        new VisionProposalGroundingValidator().validate(result);
        new VisionEvidenceBoundsValidator().validate(result, 600, 400);
        var request = payload.get();
        assertEquals("gpt-6.1-sol", request.get("model").asString());
        assertEquals(800, request.get("max_completion_tokens").asInt());
        assertFalse(request.has("max_tokens"));
        assertFalse(request.has("chat_template_kwargs"));
        assertEquals("data:image/png;base64,AQIDBA==", request.get("messages").get(1)
                .get("content").get(1).get("image_url").get("url").asString());
        var schema = request.get("response_format").get("json_schema").get("schema");
        var evidence = schema.get("properties").get("classes").get("items").get("properties").get("evidence");
        assertEquals("[\"label\"]", evidence.get("required").toString());
        assertNotNull(evidence.get("properties").get("confidence"));
        for (String coordinate : List.of("x", "y", "width", "height")) {
            assertFalse(evidence.get("properties").has(coordinate));
        }
        assertFalse(request.toString().contains(projectId.toString()));
        assertFalse(request.toString().contains(classId.toString()));
        verify(client, times(1)).chat(any());
    }

    @Test
    void rejectsSecondPassReferencesOutsideConfirmedClasses() {
        when(client.chat(any())).thenReturn(completion(proposal(), "stop"), completion("""
                {"relationships":[{"sourceRef":"c1","targetRef":"invented","type":"ASSOCIATION"}],"warnings":[]}
                """, "stop"));

        var error = assertThrows(VisionModelGatewayException.class, () -> gateway(true).analyze(image(), project()));

        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
        verify(client, times(2)).chat(any());
    }

    @Test
    void rejectsMalformedJsonWithoutRepairOrRetry() {
        when(client.chat(any())).thenReturn(completion("```json\n{}\n```", "stop"));
        var error = assertThrows(VisionModelGatewayException.class, () -> gateway(true).analyze(image(), project()));
        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
        verify(client, times(1)).chat(any());
    }

    @Test
    void rejectsTruncatedOutputBeforeSecondPass() {
        when(client.chat(any())).thenReturn(completion(proposal(), "length"));
        var error = assertThrows(VisionModelGatewayException.class, () -> gateway(true).analyze(image(), project()));
        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
        verify(client, times(1)).chat(any());
    }

    @Test
    void preservesGroundingRejectionOfPartialEvidenceCoordinates() {
        when(client.chat(any())).thenReturn(completion("""
                {"classes":[{"ref":"c1","name":"Cliente","attributes":[],
                    "evidence":{"label":"Cliente","x":10,"y":20}}],"relationships":[],"warnings":[]}
                """, "stop"));

        var result = gateway(false).analyze(image(), project());

        assertThrows(AssistantPlanningException.class, () -> new VisionProposalGroundingValidator().validate(result));
        verify(client, times(1)).chat(any());
    }

    @Test
    void propagatesProviderQuotaWithoutAnotherPass() {
        var error = new CodexApiException(429, 17, "Cuota agotada");
        when(client.chat(any())).thenThrow(error);
        assertSame(error, assertThrows(CodexApiException.class, () -> gateway(true).analyze(image(), project())));
        verify(client, times(1)).chat(any());
    }

    private CodexVisionModelGateway gateway(boolean twoPass) {
        return new CodexVisionModelGateway(mapper, new VisionPromptBuilder(), client,
                "gpt-6.1-sol", 800, twoPass, 2, 600);
    }

    private JsonNode completion(String content, String finishReason) {
        return mapper.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", finishReason,
                "message", Map.of("role", "assistant", "content", content)))));
    }

    private VisionNormalizedImage image() {
        return new VisionNormalizedImage("uml.png", "image/png", "image/png", new byte[]{1, 2, 3, 4}, 600, 400, "test", false);
    }

    private VisionProjectContext project() { return new VisionProjectContext(UUID.randomUUID(), 7L, List.of()); }

    private String proposal() {
        return """
                {"classes":[
                  {"ref":"c1","name":"Cliente","attributes":[],"evidence":{"label":"Cliente"}},
                  {"ref":"c2","name":"Pedido","attributes":[],"evidence":{"label":"Pedido"}},
                  {"ref":"c3","name":"Producto","attributes":[],"evidence":{"label":"Producto"}},
                  {"ref":"c4","name":"Categoria","attributes":[],"evidence":{"label":"Categoria"}}
                ],"relationships":[{"sourceRef":"c1","targetRef":"c2","type":"ASSOCIATION",
                  "evidence":{"label":"Cliente-Pedido"}}],"warnings":[],"confidence":0.9}
                """;
    }
}
