package com.classforge.assistant;

import com.classforge.project.application.ProjectService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRuntimeHealthServiceTests {

    @Test
    void codexReadinessUsesItsSessionAndKeepsVoiceDependentOnLocalTranscription() {
        ProjectService projectService = mock(ProjectService.class);
        AssistantRuntimeProbe probe = mock(AssistantRuntimeProbe.class);
        AssistantVisionRuntimeProbe visionProbe = mock(AssistantVisionRuntimeProbe.class);
        var codex = mock(com.classforge.assistant.codex.CodexCompletionClient.class);
        when(codex.probe("codex-text")).thenReturn(ready("codex-text"));
        when(codex.probe("codex-vision")).thenReturn(ready("codex-vision"));
        when(probe.probe("whisper.cpp", "whisper")).thenReturn(down("whisper.cpp"));
        var service = service(projectService, probe, visionProbe,
                "codex", "codex", "llama", "whisper", "vision", "unused");
        service.configureCodex(codex);

        var health = service.health(UUID.randomUUID(), UUID.randomUUID());

        assertTrue(health.readyForText());
        assertTrue(health.readyForImage());
        assertFalse(health.readyForVoice());
        verify(probe, never()).probe("llama.cpp", "llama");
        verify(visionProbe, never()).probe("vision", "unused");
    }

    @Test
    void missingCodexSessionDoesNotReportALegacyProviderAsReady() {
        var service = service(mock(ProjectService.class), mock(AssistantRuntimeProbe.class),
                mock(AssistantVisionRuntimeProbe.class), "codex", "codex", "llama", "whisper", "vision", "unused");
        var health = service.health(UUID.randomUUID(), UUID.randomUUID());
        assertFalse(health.readyForText());
        assertFalse(health.readyForImage());
        assertEquals("NOT_CONFIGURED", health.llama().state());
    }

    @Test
    void textVoiceAndImageHaveIndependentReadinessRules() {
        ProjectService projectService = mock(ProjectService.class);
        AssistantRuntimeProbe probe = mock(AssistantRuntimeProbe.class);
        AssistantVisionRuntimeProbe visionProbe = mock(AssistantVisionRuntimeProbe.class);

        when(probe.probe("llama.cpp", "http://127.0.0.1:8092"))
                .thenReturn(ready("llama.cpp"));
        when(probe.probe("whisper.cpp", "http://127.0.0.1:8093"))
                .thenReturn(down("whisper.cpp"));
        when(visionProbe.probe("http://127.0.0.1:8094", "vision-model"))
                .thenReturn(ready("vision"));

        AssistantRuntimeHealthService service = service(
                projectService, probe, visionProbe,
                "llama-cpp", "llama-cpp",
                "http://127.0.0.1:8092", "http://127.0.0.1:8093",
                "http://127.0.0.1:8094", "vision-model"
        );

        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AssistantRuntimeHealthResponse health = service.health(ownerId, projectId);

        assertTrue(health.readyForText());
        assertFalse(health.readyForVoice());
        assertTrue(health.readyForImage());
        verify(projectService).get(ownerId, projectId);
    }

    @Test
    void imageIsDownWithoutAValidVisionRuntimeButTextAndVoiceStayReady() {
        ProjectService projectService = mock(ProjectService.class);
        AssistantRuntimeProbe probe = mock(AssistantRuntimeProbe.class);
        AssistantVisionRuntimeProbe visionProbe = mock(AssistantVisionRuntimeProbe.class);

        when(probe.probe("llama.cpp", "llama")).thenReturn(ready("llama.cpp"));
        when(probe.probe("whisper.cpp", "whisper")).thenReturn(ready("whisper.cpp"));
        when(visionProbe.probe("vision", "vision-model")).thenReturn(down("vision"));

        AssistantRuntimeHealthResponse health = service(
                projectService, probe, visionProbe,
                "llama-cpp", "llama-cpp", "llama", "whisper", "vision", "vision-model"
        ).health(UUID.randomUUID(), UUID.randomUUID());

        assertTrue(health.readyForText());
        assertTrue(health.readyForVoice());
        assertFalse(health.readyForImage());
    }

    @Test
    void bedrockProvidersAreReportedAsConfiguredWithoutProbingLocalLlamaOrVisionPorts() {
        ProjectService projectService = mock(ProjectService.class);
        AssistantRuntimeProbe probe = mock(AssistantRuntimeProbe.class);
        AssistantVisionRuntimeProbe visionProbe = mock(AssistantVisionRuntimeProbe.class);
        when(probe.probe("whisper.cpp", "whisper")).thenReturn(ready("whisper.cpp"));

        AssistantRuntimeHealthResponse health = service(
                projectService, probe, visionProbe,
                "bedrock", "bedrock", "llama", "whisper", "vision", "vision-model"
        ).health(UUID.randomUUID(), UUID.randomUUID());

        assertTrue(health.readyForText());
        assertTrue(health.readyForVoice());
        assertTrue(health.readyForImage());
        assertEquals("CONFIGURED", health.llama().state());
        assertEquals("bedrock-text", health.llama().name());
        assertEquals("bedrock-vision", health.vision().name());
        verify(probe, never()).probe("llama.cpp", "llama");
        verify(visionProbe, never()).probe("vision", "vision-model");
    }

    private AssistantRuntimeHealthService service(
            ProjectService projectService,
            AssistantRuntimeProbe probe,
            AssistantVisionRuntimeProbe visionProbe,
            String textProvider,
            String visionProvider,
            String llamaUrl,
            String whisperUrl,
            String visionUrl,
            String visionModel
    ) {
        return new AssistantRuntimeHealthService(
                projectService,
                probe,
                visionProbe,
                textProvider,
                visionProvider,
                llamaUrl,
                whisperUrl,
                visionUrl,
                visionModel,
                "us-east-1",
                "us.amazon.nova-2-lite-v1:0",
                "us.amazon.nova-2-lite-v1:0"
        );
    }

    private AssistantRuntimeStatus ready(String name) {
        return new AssistantRuntimeStatus(name, true, "READY", 1, "Listo");
    }

    private AssistantRuntimeStatus down(String name) {
        return new AssistantRuntimeStatus(name, false, "UNREACHABLE", 1, "No responde");
    }
}
