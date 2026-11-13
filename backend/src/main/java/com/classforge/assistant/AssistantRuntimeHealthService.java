package com.classforge.assistant;

import com.classforge.project.application.ProjectService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AssistantRuntimeHealthService {

    private final ProjectService projectService;
    private final AssistantRuntimeProbe runtimeProbe;
    private final AssistantVisionRuntimeProbe visionRuntimeProbe;
    private final String textProvider;
    private final String visionProvider;
    private final String llamaUrl;
    private final String whisperUrl;
    private final String visionUrl;
    private final String visionModel;
    private final String bedrockRegion;
    private final String bedrockTextModel;
    private final String bedrockVisionModel;
    private com.classforge.assistant.groq.GroqHealthProbe groqHealth;
    private com.classforge.assistant.codex.CodexCompletionClient codexClient;
    private String voiceProvider = "whisper-cpp";

    @org.springframework.beans.factory.annotation.Autowired
    public void configureGroq(com.classforge.assistant.groq.GroqHealthProbe groqHealth,
            @Value("${classforge.assistant.voice.provider:whisper-cpp}") String voiceProvider) {
        this.groqHealth = groqHealth;
        this.voiceProvider = voiceProvider;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureCodex(com.classforge.assistant.codex.CodexCompletionClient codexClient) {
        this.codexClient = codexClient;
    }

    public AssistantRuntimeHealthService(
            ProjectService projectService,
            AssistantRuntimeProbe runtimeProbe,
            AssistantVisionRuntimeProbe visionRuntimeProbe,
            @Value("${classforge.assistant.text.provider:llama-cpp}") String textProvider,
            @Value("${classforge.assistant.vision.provider:llama-cpp}") String visionProvider,
            @Value("${classforge.assistant.llama-url:http://127.0.0.1:8092}") String llamaUrl,
            @Value("${classforge.assistant.whisper-url:http://127.0.0.1:8093}") String whisperUrl,
            @Value("${classforge.assistant.vision.url:http://127.0.0.1:8094}") String visionUrl,
            @Value("${classforge.assistant.vision.model:vision-model}") String visionModel,
            @Value("${classforge.assistant.bedrock.region:us-east-1}") String bedrockRegion,
            @Value("${classforge.assistant.bedrock.text-model:us.amazon.nova-2-lite-v1:0}") String bedrockTextModel,
            @Value("${classforge.assistant.bedrock.vision-model:us.amazon.nova-2-lite-v1:0}") String bedrockVisionModel
    ) {
        this.projectService = projectService;
        this.runtimeProbe = runtimeProbe;
        this.visionRuntimeProbe = visionRuntimeProbe;
        this.textProvider = normalized(textProvider, "llama-cpp");
        this.visionProvider = normalized(visionProvider, "llama-cpp");
        this.llamaUrl = llamaUrl;
        this.whisperUrl = whisperUrl;
        this.visionUrl = visionUrl;
        this.visionModel = visionModel;
        this.bedrockRegion = bedrockRegion;
        this.bedrockTextModel = bedrockTextModel;
        this.bedrockVisionModel = bedrockVisionModel;
    }

    public AssistantRuntimeHealthResponse health(UUID userId, UUID projectId) {
        projectService.get(userId, projectId);

        var groq = groqHealth != null && ("groq".equals(textProvider) || "groq".equals(visionProvider) || "groq".equals(voiceProvider))
                ? groqHealth.probe() : null;
        AssistantRuntimeStatus text = switch (textProvider) {
            case "codex" -> codexStatus("codex-text");
            case "groq" -> groq.text();
            case "bedrock" -> configuredBedrock("bedrock-text", bedrockTextModel);
            default -> runtimeProbe.probe("llama.cpp", llamaUrl);
        };

        AssistantRuntimeStatus whisper = "groq".equals(voiceProvider) && groq != null ? groq.speech() : runtimeProbe.probe("whisper.cpp", whisperUrl);

        AssistantRuntimeStatus vision = switch (visionProvider) {
            case "codex" -> codexStatus("codex-vision");
            case "groq" -> groq.vision();
            case "bedrock" -> configuredBedrock("bedrock-vision", bedrockVisionModel);
            default -> visionRuntimeProbe.probe(visionUrl, visionModel);
        };

        return new AssistantRuntimeHealthResponse(
                text.available(),
                text.available() && whisper.available(),
                vision.available(),
                text,
                whisper,
                vision,
                Instant.now()
        );
    }

    private AssistantRuntimeStatus codexStatus(String component) {
        return codexClient == null
                ? new AssistantRuntimeStatus(component, false, "NOT_CONFIGURED", 0,
                        "Inicia sesión en Codex con ChatGPT en el servidor.")
                : codexClient.probe(component);
    }

    private AssistantRuntimeStatus configuredBedrock(String component, String modelId) {
        return new AssistantRuntimeStatus(
                component,
                true,
                "CONFIGURED",
                0,
                modelId + " · " + bedrockRegion + " · IAM/model access se valida en la llamada real"
        );
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
