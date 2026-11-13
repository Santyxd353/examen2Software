package com.classforge.generation.spring.assistant;

import com.classforge.generation.spring.domain.DomainManifestPlan;
import com.classforge.generation.spring.generated.GeneratedFile;
import com.classforge.generation.spring.generated.GeneratedFileType;
import com.classforge.generation.spring.model.SpringGenerationModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generates the CU-19 Codex and local Whisper assistant inside exported Spring projects. */
public final class GeneratedAssistantRenderer {
    private final GeneratedAssistantMetadataRenderer metadata = new GeneratedAssistantMetadataRenderer();
    private final GeneratedAssistantGatewayRenderer gateways = new GeneratedAssistantGatewayRenderer();
    private final GeneratedAssistantExecutionRenderer execution = new GeneratedAssistantExecutionRenderer();
    private final GeneratedAssistantApplicationRenderer application = new GeneratedAssistantApplicationRenderer();
    private final GeneratedAssistantRuntimeRenderer runtime = new GeneratedAssistantRuntimeRenderer();

    public List<GeneratedFile> render(SpringGenerationModel model, DomainManifestPlan manifest) {
        Objects.requireNonNull(model, "model is required");
        Objects.requireNonNull(manifest, "manifest is required");
        String pkg = model.basePackage() + ".assistant";
        String path = "src/main/java/" + model.basePackage().replace('.', '/') + "/assistant/";
        List<GeneratedFile> files = new ArrayList<>();
        add(files, path + "GeneratedAssistantTypes.java", metadata.types(pkg));
        add(files, path + "GeneratedAssistantMetadata.java", metadata.metadata(pkg, manifest));
        add(files, path + "GeneratedAssistantLlamaGateway.java", gateways.llama(pkg));
        add(files, path + "GeneratedAssistantWhisperGateway.java", gateways.whisper(pkg));
        add(files, path + "GeneratedAssistantGroqClient.java", gateways.groqClient(pkg));
        add(files, path + "GeneratedAssistantHttpExecutor.java", execution.executor(pkg));
        add(files, path + "GeneratedAssistantService.java", application.service(pkg));
        add(files, path + "GeneratedAssistantController.java", application.controller(pkg));
        files.addAll(runtime.render(pkg));
        return List.copyOf(files);
    }

    private void add(List<GeneratedFile> files, String path, String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.endsWith("\n")) normalized += "\n";
        files.add(new GeneratedFile(path, GeneratedFileType.TEXT, normalized.getBytes(StandardCharsets.UTF_8)));
    }
}
