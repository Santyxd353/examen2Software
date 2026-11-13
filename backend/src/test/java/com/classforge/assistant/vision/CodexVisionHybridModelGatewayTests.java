package com.classforge.assistant.vision;

import com.classforge.assistant.codex.CodexApiException;
import com.classforge.assistant.codex.CodexCompletionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodexVisionHybridModelGatewayTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final CodexCompletionClient client = mock(CodexCompletionClient.class);
    private final AtomicReference<JsonNode> payload = new AtomicReference<>();

    @Test
    void classifiesOnlyRequestedEdgeUsingExistingImageAndSchemaPipeline() {
        reply("""
                {"edges":[{"edgeId":"E4","type":"ASSOCIATION","markerAt":"NONE",
                    "evidenceLabel":"Cliente-Pedido","confidence":0.9}],"warnings":[],"confidence":0.9}
                """);

        var result = gateway().classifyRelationship(image(), edge(), classes());

        assertEquals("E4", result.edges().getFirst().edgeId());
        assertEquals("ASSOCIATION", result.edges().getFirst().type());
        assertEquals("gpt-6.1-sol", payload.get().get("model").asString());
        assertEquals(512, payload.get().get("max_completion_tokens").asInt());
        assertFalse(payload.get().has("max_tokens"));
        assertFalse(payload.get().has("chat_template_kwargs"));
        assertEquals(mapper.readTree(VisionHybridRelationshipJsonSchema.jsonForEdge("E4")),
                payload.get().get("response_format").get("json_schema").get("schema"));
        assertEquals("data:image/png;base64,AQ==", payload.get().get("messages").get(1)
                .get("content").get(1).get("image_url").get("url").asString());
    }

    @Test
    void rejectsInventedEdge() {
        reply("{\"edges\":[{\"edgeId\":\"invented\"}]}");
        var error = assertThrows(VisionModelGatewayException.class,
                () -> gateway().classifyRelationship(image(), edge(), classes()));
        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
    }

    @Test
    void preservesMultiplicityEndpointValidationAndNoneNormalization() {
        reply("{\"edgeId\":\"E4\",\"endpoint\":\"A\",\"rawLabel\":\"NONE\",\"confidence\":0.9}");
        var result = gateway().transcribeMultiplicity(image(), edge(), VisionHybridEndpoint.A, classes().getFirst());
        assertNull(result.rawLabel());
        assertEquals(128, payload.get().get("max_completion_tokens").asInt());
        var error = assertThrows(VisionModelGatewayException.class,
                () -> gateway().transcribeMultiplicity(image(), edge(), VisionHybridEndpoint.B, classes().getLast()));
        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
    }

    @Test
    void rejectsMultiplicityOwnerOutsideVisibleCandidates() {
        reply("{\"edgeId\":\"E4\",\"endpoint\":\"A\",\"owner\":\"invented\",\"confidence\":0.9}");
        var error = assertThrows(VisionModelGatewayException.class,
                () -> gateway().attributeMultiplicity(image(), edge(), VisionHybridEndpoint.A,
                        classes().getFirst(), "1", List.of("E5")));
        assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
    }

    @ParameterizedTest
    @CsvSource({"401,false", "401,true", "429,false", "429,true"})
    void neverHidesProviderAuthOrQuotaWithSemanticFallback(int status, boolean fallback) {
        var error = new CodexApiException(status, 17, "Proveedor no disponible");
        when(client.chat(any())).thenThrow(error);
        VisionHybridAnalyzer analyzer = new VisionHybridAnalyzer(
                (image, context) -> new VisionUmlProposal("classes", classes(), List.of(), List.of(), 0.9),
                gateway(),
                (image, count) -> new UmlClassRegionDetection(List.of(), new byte[]{1}, new byte[]{1}),
                new VisionGeometryClassMappingValidator(), new VisionHybridImageFactory(),
                (image, regions) -> { throw new AssertionError("Geometry must not run after provider failure"); },
                new VisionRelationshipEvidenceSheetRenderer(), new VisionHybridProposalAssembler(), true, 2, fallback);

        assertSame(error, assertThrows(CodexApiException.class, () -> analyzer.analyze(image(), null)));
        verify(client, times(1)).chat(any());
    }

    private CodexVisionHybridModelGateway gateway() {
        return new CodexVisionHybridModelGateway(mapper, new VisionHybridPromptBuilder(), client,
                "gpt-6.1-sol", 1200, 512, 128);
    }

    private void reply(String content) {
        when(client.chat(any())).thenAnswer(invocation -> {
            payload.set(mapper.valueToTree(invocation.getArgument(0)));
            return mapper.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", content)))));
        });
    }

    private VisionNormalizedImage image() {
        return new VisionNormalizedImage("E4.png", "image/png", "image/png", new byte[]{1}, 1, 1, "test", true);
    }

    private VisionGeometryEdgeCandidate edge() {
        return new VisionGeometryEdgeCandidate("E4", "B1", "B2", "c1", "c2", 1.0, 0, 0, 1, 1, 0, 0, 1, 1);
    }

    private List<VisionClassProposal> classes() {
        return List.of(new VisionClassProposal("c1", "Cliente", List.of(), null),
                new VisionClassProposal("c2", "Pedido", List.of(), null));
    }
}
