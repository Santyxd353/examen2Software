package com.classforge.assistant.llm;

import com.classforge.assistant.codex.CodexCompletionClient;
import com.classforge.assistant.groq.GroqClient;
import com.classforge.assistant.tools.AssistantToolCallingGateway;
import com.classforge.assistant.tools.CodexToolCallingGateway;
import com.classforge.assistant.tools.GroqToolCallingGateway;
import com.classforge.assistant.tools.LlamaNativeToolCallingGateway;
import com.classforge.assistant.vision.*;
import com.classforge.projectassistant.ProjectAssistantConversationGateway;
import com.classforge.projectassistant.llm.CodexProjectAssistantGateway;
import com.classforge.projectassistant.llm.GroqProjectAssistantGateway;
import com.classforge.projectassistant.llm.LlamaCppProjectAssistantGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Provider selection with real adapters and a fake external transport, without a database or model process. */
class CodexAdapterWiringTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Providers.class);

    @Test
    void codexSelectsOneAdapterPerContractWithoutLegacyTransports() {
        runner.withPropertyValues("classforge.assistant.text.provider=codex", "classforge.assistant.vision.provider=codex")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AssistantToolCallingGateway.class)
                            .hasSingleBean(ProjectAssistantConversationGateway.class)
                            .hasSingleBean(VisionModelGateway.class).hasSingleBean(VisionHybridModelGateway.class);
                    assertThat(context.getBean(AssistantToolCallingGateway.class)).isExactlyInstanceOf(CodexToolCallingGateway.class);
                    assertThat(context.getBean(ProjectAssistantConversationGateway.class)).isExactlyInstanceOf(CodexProjectAssistantGateway.class);
                    assertThat(context.getBean(VisionModelGateway.class)).isExactlyInstanceOf(CodexVisionModelGateway.class);
                    assertThat(context.getBean(VisionHybridModelGateway.class)).isExactlyInstanceOf(CodexVisionHybridModelGateway.class);
                    assertThat(context).doesNotHaveBean(GroqProjectAssistantGateway.class).doesNotHaveBean(GroqVisionModelGateway.class)
                            .doesNotHaveBean(LlamaNativeToolCallingGateway.class).doesNotHaveBean(LlamaCppProjectAssistantGateway.class);
                    verifyNoInteractions(context.getBean(CodexCompletionClient.class), context.getBean(GroqClient.class));
                });
    }

    @Test
    void legacyGroqRemainsAnExplicitProviderChoice() {
        runner.withPropertyValues("classforge.assistant.text.provider=groq", "classforge.assistant.vision.provider=groq")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AssistantToolCallingGateway.class)
                            .hasSingleBean(ProjectAssistantConversationGateway.class)
                            .hasSingleBean(VisionModelGateway.class).hasSingleBean(VisionHybridModelGateway.class);
                    assertThat(context.getBean(AssistantToolCallingGateway.class)).isExactlyInstanceOf(GroqToolCallingGateway.class);
                    assertThat(context.getBean(ProjectAssistantConversationGateway.class)).isExactlyInstanceOf(GroqProjectAssistantGateway.class);
                    assertThat(context.getBean(VisionModelGateway.class)).isExactlyInstanceOf(GroqVisionModelGateway.class);
                    assertThat(context.getBean(VisionHybridModelGateway.class)).isExactlyInstanceOf(GroqVisionHybridModelGateway.class);
                    assertThat(context).doesNotHaveBean(CodexToolCallingGateway.class)
                            .doesNotHaveBean(CodexProjectAssistantGateway.class).doesNotHaveBean(CodexVisionModelGateway.class)
                            .doesNotHaveBean(CodexVisionHybridModelGateway.class);
                });
    }

    @Test
    void denseImageDefaultsToOneSemanticCallWithoutHybridOrRelationshipPasses() {
        runner.withUserConfiguration(HybridProvider.class)
                .withPropertyValues("classforge.assistant.text.provider=codex", "classforge.assistant.vision.provider=codex")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JsonMapper mapper = context.getBean(JsonMapper.class);
                    CodexCompletionClient client = context.getBean(CodexCompletionClient.class);
                    when(client.chat(any())).thenReturn(mapper.valueToTree(Map.of("choices", List.of(Map.of(
                            "finish_reason", "stop", "message", Map.of("content", """
                                    {"classes":[
                                      {"ref":"c1","name":"Cliente","attributes":[],"evidence":{"label":"Cliente"}},
                                      {"ref":"c2","name":"Pedido","attributes":[],"evidence":{"label":"Pedido"}},
                                      {"ref":"c3","name":"Producto","attributes":[],"evidence":{"label":"Producto"}},
                                      {"ref":"c4","name":"Categoria","attributes":[],"evidence":{"label":"Categoria"}}
                                    ],"relationships":[{"sourceRef":"c1","targetRef":"c2","type":"ASSOCIATION",
                                      "evidence":{"label":"Cliente-Pedido"}}],"warnings":[]}
                                    """))))));

                    var result = context.getBean(VisionModelGateway.class).analyze(
                            new VisionNormalizedImage("uml.png", "image/png", "image/png", new byte[]{1}, 600, 400, "test", false),
                            new VisionProjectContext(UUID.randomUUID(), 7L, List.of()));

                    assertEquals(4, result.safeClasses().size());
                    assertEquals(1, result.safeRelationships().size());
                    new VisionProposalGroundingValidator().validate(result);
                    verify(client, times(1)).chat(any());
                    verifyNoInteractions(context.getBean(UmlClassRegionDetector.class), context.getBean(UmlDiagramGeometryAnalyzer.class));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({CodexToolCallingGateway.class, CodexProjectAssistantGateway.class,
            CodexVisionModelGateway.class, CodexVisionHybridModelGateway.class,
            GroqToolCallingGateway.class, GroqProjectAssistantGateway.class,
            GroqVisionModelGateway.class, GroqVisionHybridModelGateway.class,
            LlamaNativeToolCallingGateway.class, LlamaCppProjectAssistantGateway.class,
            LlamaCppVisionModelGateway.class, LlamaCppVisionHybridModelGateway.class,
            UnconfiguredVisionModelGateway.class, UnconfiguredVisionHybridModelGateway.class,
            VisionPromptBuilder.class, VisionHybridPromptBuilder.class})
    static class Providers {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean CodexCompletionClient codexCompletionClient() { return mock(CodexCompletionClient.class); }
        @Bean GroqClient groqClient() { return mock(GroqClient.class); }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(HybridVisionModelGateway.class)
    static class HybridProvider {
        @Bean UmlClassRegionDetector detector() { return mock(UmlClassRegionDetector.class); }
        @Bean UmlDiagramGeometryAnalyzer geometry() { return mock(UmlDiagramGeometryAnalyzer.class); }
        @Bean VisionGeometryClassMappingValidator mapping() { return new VisionGeometryClassMappingValidator(); }
        @Bean VisionHybridImageFactory images() { return new VisionHybridImageFactory(); }
        @Bean VisionRelationshipEvidenceSheetRenderer sheets() { return new VisionRelationshipEvidenceSheetRenderer(); }
        @Bean VisionHybridProposalAssembler assembler() { return new VisionHybridProposalAssembler(); }
    }
}
