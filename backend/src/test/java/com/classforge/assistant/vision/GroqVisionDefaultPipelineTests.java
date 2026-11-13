package com.classforge.assistant.vision;

import com.classforge.assistant.groq.GroqClient;
import com.classforge.assistant.groq.GroqApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Production defaults must fit one Groq inference even for diagrams with several classes. */
class GroqVisionDefaultPipelineTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("classforge.assistant.vision.provider=groq")
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("production-defaults",
                            new FileSystemResource("src/main/resources/application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            })
            .withUserConfiguration(Providers.class);

    @Test
    void denseImageKeepsValidatedClassesAndRelationshipsWithOneProviderCallByDefault() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            when(context.getBean(UmlClassRegionDetector.class).detect(any(), anyInt()))
                    .thenThrow(new GroqApiException(429, 19, "Free quota exhausted by additional passes"));
            var result = context.getBean(VisionModelGateway.class).analyze(image(), project());

            assertEquals(4, result.safeClasses().size());
            assertEquals(1, result.safeRelationships().size());
            new VisionProposalGroundingValidator().validate(result);
            new VisionEvidenceBoundsValidator().validate(result, 600, 400);
            verify(context.getBean(GroqClient.class), times(1)).chat(any());
            verifyNoInteractions(context.getBean(UmlClassRegionDetector.class), context.getBean(VisionHybridModelGateway.class));
        });
    }

    @Test
    void explicitlyEnabledHybridRemainsAvailableAndFailsClosed() {
        runner.withPropertyValues("classforge.assistant.vision.dense-hybrid.enabled=true").run(context -> {
            assertNull(context.getStartupFailure());
            var detector = context.getBean(UmlClassRegionDetector.class);
            when(detector.detect(any(), anyInt())).thenThrow(new IllegalStateException("unavailable geometry"));

            var error = assertThrows(VisionModelGatewayException.class,
                    () -> context.getBean(VisionModelGateway.class).analyze(image(), project()));
            assertEquals(VisionModelGatewayException.Reason.OUTPUT_CONTRACT, error.reason());
            verify(detector).detect(any(), eq(4));
        });
    }

    private VisionNormalizedImage image() {
        return new VisionNormalizedImage("fixture.png", "image/png", "image/png", new byte[]{1}, 600, 400, "test", false);
    }

    private VisionProjectContext project() {
        return new VisionProjectContext(UUID.randomUUID(), 0, List.of());
    }

    @Configuration(proxyBeanMethods = false)
    @Import({GroqVisionModelGateway.class, HybridVisionModelGateway.class, VisionPromptBuilder.class})
    static class Providers {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean GroqClient groqClient(JsonMapper mapper) {
            GroqClient client = mock(GroqClient.class);
            String content = """
                    {"classes":[
                      {"ref":"c1","name":"Cliente","attributes":[],"evidence":{"label":"Cliente"}},
                      {"ref":"c2","name":"Pedido","attributes":[],"evidence":{"label":"Pedido"}},
                      {"ref":"c3","name":"Producto","attributes":[],"evidence":{"label":"Producto"}},
                      {"ref":"c4","name":"Categoria","attributes":[],"evidence":{"label":"Categoria"}}
                    ],"relationships":[{"sourceRef":"c1","targetRef":"c2","type":"ASSOCIATION",
                       "evidence":{"label":"Cliente-Pedido"}}],"warnings":[]}
                    """;
            when(client.chat(any())).thenReturn(mapper.valueToTree(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", content))))));
            return client;
        }
        @Bean VisionHybridModelGateway hybridGateway() { return mock(VisionHybridModelGateway.class); }
        @Bean UmlClassRegionDetector detector() { return mock(UmlClassRegionDetector.class); }
        @Bean UmlDiagramGeometryAnalyzer geometry() { return mock(UmlDiagramGeometryAnalyzer.class); }
        @Bean VisionGeometryClassMappingValidator mapping() { return new VisionGeometryClassMappingValidator(); }
        @Bean VisionHybridImageFactory images() { return new VisionHybridImageFactory(); }
        @Bean VisionRelationshipEvidenceSheetRenderer sheets() { return new VisionRelationshipEvidenceSheetRenderer(); }
        @Bean VisionHybridProposalAssembler assembler() { return new VisionHybridProposalAssembler(); }
    }
}
