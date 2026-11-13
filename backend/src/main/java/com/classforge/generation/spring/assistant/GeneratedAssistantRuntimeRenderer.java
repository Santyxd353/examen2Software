package com.classforge.generation.spring.assistant;

import com.classforge.generation.spring.generated.GeneratedFile;
import com.classforge.generation.spring.generated.GeneratedFileType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Repackages the shared source transport; credentials and operator configuration are never resources. */
final class GeneratedAssistantRuntimeRenderer {
    private static final List<String> SOURCES = List.of(
            "codex/CodexCompletionClient.java", "codex/CodexApiException.java", "codex/CodexFiles.java",
            "codex/CodexJsonSchema.java", "codex/CodexRequestCodec.java", "codex/CodexProcessRunner.java",
            "llm/AssistantChatClient.java", "AssistantRuntimeStatus.java", "SpeechToTextGateway.java",
            "WhisperCppSpeechToTextGateway.java");

    List<GeneratedFile> render(String assistantPackage) {
        String runtimePackage = assistantPackage + ".runtime";
        String path = "src/main/java/" + runtimePackage.replace('.', '/') + "/";
        List<GeneratedFile> result = new ArrayList<>();
        for (String source : SOURCES) {
            String resource = "/generation/spring/assistant-runtime/com/classforge/assistant/" + source;
            try (var input = getClass().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Falta una fuente del transporte generado: " + source);
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")
                        .replace("com.classforge.assistant", runtimePackage)
                        .replace("${classforge.assistant.", "${app.assistant.")
                        .replaceAll("(?m)^@Component\\s*\n", "")
                        .replaceAll("(?s)@org\\.springframework\\.boot\\.autoconfigure\\.condition\\.ConditionalOnProperty\\(.*?\\)\\s*", "")
                        .replace("propuestas UML", "propuestas de datos")
                        .replace("UML, clase, atributo, asociacion, agregacion, composicion, generalizacion, multiplicidad",
                                "datos, consulta, crear, actualizar, relacion");
                result.add(file(path + source, text));
            } catch (IOException exception) {
                throw new IllegalStateException("No se pudo incluir el transporte del asistente generado.", exception);
            }
        }
        // The export does not depend on ClassForge's UML planning diagnostics.
        result.add(file(path + "AssistantPlanningException.java", """
                package %s;

                public class AssistantPlanningException extends RuntimeException {
                    public AssistantPlanningException(String message) { super(message); }
                    public AssistantPlanningException(String message, Throwable cause) { super(message, cause); }
                }
                """.formatted(runtimePackage)));
        return List.copyOf(result);
    }

    private GeneratedFile file(String path, String text) {
        return new GeneratedFile(path, GeneratedFileType.TEXT, text.getBytes(StandardCharsets.UTF_8));
    }
}
