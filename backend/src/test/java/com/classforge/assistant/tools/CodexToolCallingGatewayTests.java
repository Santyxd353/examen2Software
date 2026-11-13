package com.classforge.assistant.tools;

import com.classforge.assistant.AssistantEntityReferenceResolver;
import com.classforge.assistant.AssistantIntentHintResolver;
import com.classforge.assistant.AssistantPlanningException;
import com.classforge.assistant.codex.CodexApiException;
import com.classforge.assistant.codex.CodexCompletionClient;
import com.classforge.project.domain.document.ProjectDocument;
import com.classforge.project.domain.document.UmlClass;
import com.classforge.project.domain.document.UmlModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodexToolCallingGatewayTests {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final DynamicUmlToolCatalog catalogs = new DynamicUmlToolCatalog(new AssistantIntentHintResolver());
    private final CodexCompletionClient client = mock(CodexCompletionClient.class);
    private final AtomicReference<JsonNode> payload = new AtomicReference<>();

    @Test
    void usesClosedCatalogAndExistingResolverWithoutApplyingDocumentChanges() {
        UUID classId = UUID.randomUUID();
        var document = new ProjectDocument("1.0", new UmlModel(
                List.of(new UmlClass(classId, "Cliente", List.of())), List.of()), null);
        var catalog = catalogs.buildForTools(document, List.of(AssistantToolName.RENAME_CLASS));
        reply(completion("rename_class", "{\"existing_class\":\"Cliente\",\"new_name\":\"Comprador\"}"));

        var calls = gateway().call("Renombra Cliente a Comprador", document, catalog);
        var resolved = new UmlToolCallResolver(new AssistantEntityReferenceResolver(), new AssistantLiteralArgumentBinder())
                .resolve("Renombra Cliente a Comprador", calls, catalog, document);

        assertEquals(classId, resolved.references().getFirst().elementId());
        assertEquals("Cliente", document.umlModel().classes().getFirst().name());
        assertEquals("gpt-6.1-sol", payload.get().get("model").asString());
        assertEquals("required", payload.get().get("tool_choice").asString());
        assertFalse(payload.get().get("parallel_tool_calls").asBoolean());
        var schema = payload.get().get("tools").get(0).get("function").get("parameters");
        assertEquals("Cliente", schema.get("properties").get("existing_class").get("enum").get(0).asString());
        assertFalse(schema.get("additionalProperties").asBoolean());
        verify(client, times(1)).chat(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{}", "{\"name\":12}", "{\"name\":\"\"}",
            "{\"name\":\"Cliente\",\"unexpected\":true}"})
    void rejectsArgumentsOutsideCurrentSchema(String arguments) {
        reply(completion("create_class", arguments));
        var catalog = catalogs.buildForTools(ProjectDocument.empty(), List.of(AssistantToolName.CREATE_CLASS));

        assertThrows(AssistantPlanningException.class,
                () -> gateway().call("Crea Cliente", ProjectDocument.empty(), catalog));
        verify(client, times(1)).chat(any());
    }

    @Test
    void rejectsInventedExistingReference() {
        var document = new ProjectDocument("1.0", new UmlModel(
                List.of(new UmlClass(UUID.randomUUID(), "Cliente", List.of())), List.of()), null);
        reply(completion("rename_class", "{\"existing_class\":\"Inventada\",\"new_name\":\"Comprador\"}"));
        var catalog = catalogs.buildForTools(document, List.of(AssistantToolName.RENAME_CLASS));
        assertThrows(AssistantPlanningException.class, () -> gateway().call("Renombra Cliente", document, catalog));
    }

    @Test
    void rejectsToolOutsideExposedCatalog() {
        reply(completion("delete_class", "{\"existing_class\":\"Cliente\"}"));
        var catalog = catalogs.buildForTools(ProjectDocument.empty(), List.of(AssistantToolName.CREATE_CLASS));
        assertThrows(AssistantPlanningException.class,
                () -> gateway().call("Crea Cliente", ProjectDocument.empty(), catalog));
    }

    @Test
    void rejectsMultipleCallsInOneStep() {
        var call = Map.of("id", "call-1", "type", "function", "function",
                Map.of("name", "create_class", "arguments", "{\"name\":\"Cliente\"}"));
        reply(mapper.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls",
                "message", Map.of("tool_calls", List.of(call, call)))))));
        var catalog = catalogs.buildForTools(ProjectDocument.empty(), List.of(AssistantToolName.CREATE_CLASS));
        assertThrows(AssistantPlanningException.class,
                () -> gateway().call("Crea Cliente", ProjectDocument.empty(), catalog));
    }

    @Test
    void preservesAcceptedHistoryAndToolResultsForSequentialPlanning() {
        reply(completion("create_class", "{\"name\":\"Factura\"}"));
        var catalog = catalogs.buildForTools(ProjectDocument.empty(), List.of(AssistantToolName.CREATE_CLASS));
        var accepted = new AssistantToolInvocation("accepted-1", AssistantToolName.CREATE_CLASS,
                mapper.readTree("{\"name\":\"Cliente\"}"));

        gateway().call("Crea Cliente y Factura", ProjectDocument.empty(), catalog,
                List.of(new AssistantToolConversationTurn(List.of(accepted), List.of("Cliente preparado en preview"))));

        var messages = payload.get().get("messages");
        assertEquals(4, messages.size());
        assertEquals("Crea Cliente y Factura", messages.get(1).get("content").asString());
        assertEquals("accepted-1", messages.get(2).get("tool_calls").get(0).get("id").asString());
        assertEquals("accepted-1", messages.get(3).get("tool_call_id").asString());
        assertEquals("Cliente preparado en preview", messages.get(3).get("content").asString());
    }

    @Test
    void propagatesProviderQuotaWithoutRetryOrWrapping() {
        var error = new CodexApiException(429, 23, "Cuota agotada");
        when(client.chat(any())).thenThrow(error);
        var catalog = catalogs.buildForTools(ProjectDocument.empty(), List.of(AssistantToolName.CREATE_CLASS));
        assertSame(error, assertThrows(CodexApiException.class,
                () -> gateway().call("Crea Cliente", ProjectDocument.empty(), catalog)));
        verify(client, times(1)).chat(any());
    }

    private CodexToolCallingGateway gateway() {
        return new CodexToolCallingGateway(client, mapper, "gpt-6.1-sol", 2048, 120000);
    }

    private void reply(JsonNode response) {
        when(client.chat(any())).thenAnswer(invocation -> {
            payload.set(mapper.valueToTree(invocation.getArgument(0)));
            return response;
        });
    }

    private JsonNode completion(String name, String arguments) {
        return mapper.valueToTree(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of(
                "role", "assistant", "tool_calls", List.of(Map.of("id", "call-1", "type", "function",
                        "function", Map.of("name", name, "arguments", arguments))))))));
    }
}
