package com.classforge.generation.spring.assistant;

import static org.junit.jupiter.api.Assertions.*;

import com.classforge.generation.spring.application.SpringBootGenerationOptions;
import com.classforge.generation.spring.generated.GeneratedFile;
import com.classforge.generation.spring.generated.GeneratedProject;
import com.classforge.generation.spring.generated.GeneratedProjectValidator;
import com.classforge.generation.spring.generated.GeneratedProjectException;
import com.classforge.generation.spring.model.*;
import com.classforge.generation.spring.rendering.SpringFreeMarkerRenderer;
import com.classforge.generation.spring.rendering.SpringProjectRenderer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeneratedAssistantRenderingTests {
    @Test
    void rendersGroundedNativeToolAssistantAndVoiceClientsWithoutLeakingSensitivePreviewValues() {
        Fixture fixture = fixture();
        SpringProjectRenderer renderer = new SpringProjectRenderer(new SpringFreeMarkerRenderer(), new GeneratedProjectValidator() {
            @Override public void validate(GeneratedProject project) {
                try { super.validate(project); }
                catch (GeneratedProjectException exception) { fail(exception.diagnostics().toString()); }
            }
        });

        GeneratedProject simple = renderer.render(fixture.model(), SpringBootGenerationOptions.simpleCrud(false, "#7C3AED"));
        assertAssistant(simple);
        String configuration = text(simple, "src/main/resources/application.yml");
        assertTrue(configuration.contains("api-key: ${GROQ_API_KEY:}"));
        assertTrue(configuration.contains("base-url: ${GROQ_BASE_URL:https://api.groq.com/openai/v1}"));
        assertTrue(configuration.contains("text-model: ${GROQ_TEXT_MODEL:openai/gpt-oss-120b}"));
        assertTrue(configuration.contains("speech-model: ${GROQ_SPEECH_MODEL:whisper-large-v3-turbo}"));
        assertTrue(configuration.contains("provider: ${ASSISTANT_TEXT_PROVIDER:codex}"));
        assertTrue(configuration.contains("provider: ${ASSISTANT_VOICE_PROVIDER:whisper-cpp}"));
        assertTrue(configuration.contains("home: ${CODEX_HOME:}"));
        assertTrue(configuration.contains("model: ${CODEX_MODEL:gpt-6.1-sol}"));
        assertFalse(configuration.contains("LLAMA_"));
        assertTrue(configuration.contains("WHISPER_URL"));
        String readme = text(simple, "README.md");
        assertTrue(readme.contains("GROQ_API_KEY"));
        assertTrue(readme.contains("CODEX_EXECUTABLE"));
        assertTrue(readme.contains("CODEX_HOME"));
        assertTrue(readme.contains("429"));
        assertFalse(readme.contains("llama.cpp"));
        for (GeneratedFile file : simple.files()) {
            String content = new String(file.content(), StandardCharsets.UTF_8);
            assertFalse(content.contains("gsk_"), file.path());
            if (file.path().startsWith("frontend/") || file.path().startsWith("mobile/")) {
                assertFalse(content.contains("GROQ_API_KEY"), file.path());
                assertFalse(content.contains("api.groq.com"), file.path());
            }
        }
        assertTrue(text(simple, "frontend/src/app/assistant/assistant.component.ts").contains("previewToken"));
        assertFalse(text(simple, "frontend/src/app/assistant/assistant.component.ts").contains("pending.command"));
        assertTrue(text(simple, "frontend/src/app/assistant/browser-wav-recorder.service.ts").contains("16_000"));
        assertTrue(text(simple, "mobile/pubspec.yaml").contains("record: ^7.1.1"));
        assertTrue(text(simple, "mobile/lib/assistant/assistant_page.dart").contains("AudioEncoder.wav"));
        assertTrue(text(simple, "mobile/android/app/src/main/AndroidManifest.xml").contains("android.permission.RECORD_AUDIO"));

        GeneratedProject auth = renderer.render(
                fixture.model(),
                SpringBootGenerationOptions.authenticated(false, fixture.classId(), fixture.usernameId(), fixture.passwordId(), "#0F766E")
        );
        assertAssistant(auth);
        String types = assistantText(auth, "GeneratedAssistantTypes.java");
        assertTrue(types.contains("record RelationValue"));
        String metadata = assistantText(auth, "GeneratedAssistantMetadata.java");
        assertTrue(metadata.contains("field.sensitive() ? \"***\" : value"));
        assertTrue(metadata.contains("preferredSelectorField"));
        assertTrue(metadata.contains("speechPrompt()"));
        assertTrue(metadata.contains("relativeDateTime"));
        assertTrue(metadata.contains("parseDateTime"));
        assertTrue(metadata.contains("date.equals(now.toLocalDate()) ? now : date.atStartOfDay()"));
        assertTrue(metadata.contains("acepta-expresion-temporal"));
        String service = assistantText(auth, "GeneratedAssistantService.java");
        assertTrue(service.contains("pending.put(token"));
        assertTrue(service.contains("PREVIEW_TTL"));
        assertTrue(service.contains("llama.command(input, route, safeMessage(firstFailure))"));
        assertTrue(service.contains("Whisper entendio:"));
        assertFalse(service.contains("new PlanResponse(source, input, intent.name(), summary, true, token, json"));
        String mobileApi = text(auth, "mobile/lib/assistant/assistant_api.dart");
        assertTrue(mobileApi.contains("TokenStore"));
        assertTrue(mobileApi.contains("Authorization"));
    }

    private void assertAssistant(GeneratedProject project) {
        for (String name : List.of(
                "GeneratedAssistantTypes.java",
                "GeneratedAssistantMetadata.java",
                "GeneratedAssistantLlamaGateway.java",
                "GeneratedAssistantWhisperGateway.java",
                "GeneratedAssistantGroqClient.java",
                "GeneratedAssistantHttpExecutor.java",
                "GeneratedAssistantService.java",
                "GeneratedAssistantController.java"
        )) {
            assertNotNull(assistantFile(project, name), name);
        }
        String controller = assistantText(project, "GeneratedAssistantController.java");
        assertTrue(controller.contains("@RequestMapping(\"/api/assistant\")"));
        assertTrue(controller.contains("@PostMapping(\"/plan\")"));
        assertTrue(controller.contains("@PostMapping(value = \"/voice\""));
        assertTrue(controller.contains("@PostMapping(\"/apply\")"));
        String llama = assistantText(project, "GeneratedAssistantLlamaGateway.java");
        assertTrue(llama.contains("route_data_request"));
        assertTrue(llama.contains("groq.chat(request)"));
        assertTrue(llama.contains("max_completion_tokens"));
        assertFalse(llama.contains("/props"));
        assertFalse(llama.contains("max_tokens"));
        assertTrue(llama.contains("tool_choice"));
        assertTrue(llama.contains("filterFieldNames(entity)"));
        assertTrue(llama.contains("valueFieldNames(entity, intent)"));
        assertTrue(llama.contains("NO inventes campos tecnicos como propietarioId"));
        assertTrue(llama.contains("REGLA ESTRICTA DE FECHAS"));
        assertTrue(llama.contains("NO calcules una fecha absoluta"));
        assertTrue(llama.contains("LocalDateTime.now().withNano(0)"));
        assertTrue(llama.contains("maxItems"));
        String whisper = assistantText(project, "GeneratedAssistantWhisperGateway.java");
        assertTrue(whisper.contains("groq.transcribe"));
        String groq = assistantText(project, "GeneratedAssistantGroqClient.java");
        assertTrue(groq.contains("/chat/completions"));
        assertTrue(groq.contains("/audio/transcriptions"));
        assertTrue(groq.contains("Authorization"));
        assertTrue(groq.contains("Bearer "));
        assertTrue(groq.contains("audio/wav"));
        assertTrue(groq.contains("response_format"));
        assertTrue(groq.contains("Retry-After"));
        assertFalse(groq.contains("temperature_inc"));
        assertFalse(groq.contains("/inference"));
        assertFalse(groq.contains("/props"));
        String executor = assistantText(project, "GeneratedAssistantHttpExecutor.java");
        assertTrue(executor.contains("materializeValues"));
        assertTrue(executor.contains("pending.selector()"));
    }

    private GeneratedFile assistantFile(GeneratedProject project, String name) {
        return project.files().stream().filter(file -> file.path().endsWith("/assistant/" + name)).findFirst().orElse(null);
    }

    private String assistantText(GeneratedProject project, String name) {
        GeneratedFile file = assistantFile(project, name);
        assertNotNull(file, name);
        return new String(file.content(), StandardCharsets.UTF_8);
    }

    private String text(GeneratedProject project, String path) {
        GeneratedFile file = project.files().stream().filter(candidate -> candidate.path().equals(path)).findFirst().orElseThrow();
        return new String(file.content(), StandardCharsets.UTF_8);
    }

    Fixture fixture() {
        UUID classId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID idId = UUID.fromString("11111111-1111-1111-1111-111111111112");
        UUID usernameId = UUID.fromString("11111111-1111-1111-1111-111111111113");
        UUID passwordId = UUID.fromString("11111111-1111-1111-1111-111111111114");
        SpringEntityIdModel id = new SpringEntityIdModel(
                SpringIdKind.SIMPLE,
                SpringJavaType.UUID,
                null,
                List.of(new SpringIdFieldModel(idId, "id", "id", SpringJavaType.UUID)),
                true
        );
        SpringEntityModel entity = new SpringEntityModel(
                classId,
                "Usuario",
                "Usuario",
                "usuario",
                new SpringInheritanceModel(SpringInheritanceKind.NONE, null, List.of()),
                id,
                List.of(
                        new SpringScalarFieldModel(idId, "id", "id", "id", SpringJavaType.UUID, false, true),
                        new SpringScalarFieldModel(usernameId, "username", "username", "username", SpringJavaType.STRING, false, false),
                        new SpringScalarFieldModel(passwordId, "password", "password", "password", SpringJavaType.STRING, false, false)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        SpringGenerationModel model = new SpringGenerationModel(
                "1.0",
                "assistant-demo",
                "com.example.assistant",
                "AssistantApplication",
                "21",
                "4.0.8",
                "9.2.0",
                List.of(entity),
                List.of(new SpringRepositoryModel("UsuarioRepository", "Usuario", "UUID", "java.util.UUID", false))
        );
        return new Fixture(model, classId, usernameId, passwordId);
    }

    record Fixture(SpringGenerationModel model, UUID classId, UUID usernameId, UUID passwordId) { }
}
