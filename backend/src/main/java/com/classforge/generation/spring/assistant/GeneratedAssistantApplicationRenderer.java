package com.classforge.generation.spring.assistant;

import com.classforge.generation.spring.domain.DomainManifestPlan;
import java.util.*;
import java.util.stream.Collectors;

final class GeneratedAssistantApplicationRenderer {

    String service(String pkg) {
        return template("""
                package __PACKAGE__;

                import static __PACKAGE__.GeneratedAssistantTypes.*;

                import java.time.Duration;
                import java.time.Instant;
                import java.util.Map;
                import java.util.UUID;
                import java.util.concurrent.ConcurrentHashMap;
                import org.springframework.stereotype.Service;
                import org.springframework.web.multipart.MultipartFile;
                import tools.jackson.databind.JsonNode;

                @Service
                public class GeneratedAssistantService {
                    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
                    private final GeneratedAssistantLlamaGateway llama;
                    private final GeneratedAssistantWhisperGateway whisper;
                    private final GeneratedAssistantHttpExecutor executor;
                    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

                    public GeneratedAssistantService(GeneratedAssistantLlamaGateway llama, GeneratedAssistantWhisperGateway whisper,
                            GeneratedAssistantHttpExecutor executor) {
                        this.llama = llama; this.whisper = whisper; this.executor = executor;
                    }

                    public PlanResponse plan(String text, String authorization) { return planText(text, authorization, "TEXT"); }
                    public boolean isConfigured() { return llama.isConfigured(); }
                    public boolean isVoiceConfigured() { return llama.isConfigured() && whisper.isConfigured(); }
                    public String providerName() { return llama.providerName(); }
                    public String speechProviderName() { return whisper.providerName(); }
                    public PlanResponse voice(MultipartFile audio, String authorization) {
                        String transcript = whisper.transcribe(audio);
                        try {
                            return planText(transcript, authorization, "VOICE");
                        } catch (IllegalArgumentException exception) {
                            throw new IllegalArgumentException("Whisper entendio: \\\"" + abbreviate(transcript, 220) + "\\\". "
                                    + (exception.getMessage() == null ? "No se pudo planificar la instruccion." : exception.getMessage()), exception);
                        }
                    }

                    private PlanResponse planText(String text, String authorization, String source) {
                        String input = text == null ? "" : text.trim();
                        if (input.isBlank()) throw new IllegalArgumentException("Escribe o dicta una instruccion.");
                        if (input.length() > 1000) throw new IllegalArgumentException("La instruccion supera 1000 caracteres.");
                        cleanup();
                        RouteDecision route = llama.route(input);
                        Command command = resolveCommand(input, route);
                        String summary = GeneratedAssistantMetadata.summary(command);
                        if (!route.intent().mutating()) {
                            JsonNode result = executor.execute(command, authorization);
                            return new PlanResponse(source, input, route.intent().name(), summary, false, null, result);
                        }
                        String token = UUID.randomUUID().toString();
                        pending.put(token, new Pending(command, binding(authorization), Instant.now().plus(PREVIEW_TTL)));
                        return new PlanResponse(source, input, route.intent().name(), summary, true, token, null);
                    }

                    private Command resolveCommand(String input, RouteDecision route) {
                        RawCommand first = llama.command(input, route);
                        try {
                            return GeneratedAssistantMetadata.resolve(route.intent(), first);
                        } catch (IllegalArgumentException firstFailure) {
                            RawCommand repaired = llama.command(input, route, safeMessage(firstFailure));
                            try {
                                return GeneratedAssistantMetadata.resolve(route.intent(), repaired);
                            } catch (IllegalArgumentException secondFailure) {
                                secondFailure.addSuppressed(firstFailure);
                                throw secondFailure;
                            }
                        }
                    }

                    public PlanResponse apply(String previewToken, String authorization) {
                        cleanup();
                        if (previewToken == null || previewToken.isBlank()) throw new IllegalArgumentException("previewToken es obligatorio.");
                        Pending item = pending.remove(previewToken);
                        if (item == null || item.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("El preview expiro o ya fue consumido.");
                        if (!item.authorizationBinding().equals(binding(authorization))) throw new IllegalArgumentException("El preview pertenece a otra sesion.");
                        JsonNode result = executor.execute(item.command(), authorization);
                        return new PlanResponse("APPLY", "", item.command().intent().name(), GeneratedAssistantMetadata.summary(item.command()), false, null, result);
                    }

                    private void cleanup() { Instant now = Instant.now(); pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now)); }
                    private String binding(String authorization) { return authorization == null ? "" : authorization; }
                    private String safeMessage(RuntimeException exception) {
                        String message = exception.getMessage();
                        return message == null || message.isBlank() ? "El comando no cumple el contrato de datos." : abbreviate(message, 300);
                    }
                    private String abbreviate(String value, int max) {
                        if (value == null) return "";
                        return value.length() <= max ? value : value.substring(0, max) + "...";
                    }
                    private record Pending(Command command, String authorizationBinding, Instant expiresAt) { }
                }
                """, pkg);
    }

    String controller(String pkg) {
        return template("""
                package __PACKAGE__;

                import static __PACKAGE__.GeneratedAssistantTypes.*;

                import java.util.Map;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.MediaType;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.ResponseStatus;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.multipart.MultipartFile;

                @RestController
                @RequestMapping("/api/assistant")
                public class GeneratedAssistantController {
                    private final GeneratedAssistantService service;
                    public GeneratedAssistantController(GeneratedAssistantService service) { this.service = service; }

                    @PostMapping("/plan")
                    public PlanResponse plan(@RequestBody PlanRequest request,
                            @RequestHeader(name = "Authorization", required = false) String authorization) {
                        return service.plan(request == null ? null : request.text(), authorization);
                    }

                    @PostMapping(value = "/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
                    public PlanResponse voice(@RequestParam("audio") MultipartFile audio,
                            @RequestHeader(name = "Authorization", required = false) String authorization) {
                        return service.voice(audio, authorization);
                    }

                    @PostMapping("/apply")
                    public PlanResponse apply(@RequestBody ApplyRequest request,
                            @RequestHeader(name = "Authorization", required = false) String authorization) {
                        return service.apply(request == null ? null : request.previewToken(), authorization);
                    }

                    @GetMapping("/capabilities")
                    public Map<String, Object> capabilities() {
                        return Map.of("provider", service.providerName(), "speechProvider", service.speechProviderName(),
                                "text", service.isConfigured(), "voice", service.isVoiceConfigured(),
                                "intents", java.util.Arrays.stream(Intent.values()).map(Enum::name).toList());
                    }

                    @ExceptionHandler(IllegalArgumentException.class)
                    @ResponseStatus(HttpStatus.BAD_REQUEST)
                    public Map<String, String> badRequest(IllegalArgumentException exception) {
                        return Map.of("error", "ASSISTANT_PLAN_INVALID", "message", exception.getMessage() == null ? "Comando invalido." : exception.getMessage());
                    }

                    @ExceptionHandler(IllegalStateException.class)
                    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    public Map<String, String> unavailable(IllegalStateException exception) {
                        return Map.of("error", "ASSISTANT_RUNTIME_UNAVAILABLE", "message", "No se pudo completar la solicitud del asistente.");
                    }

                    @ExceptionHandler(GeneratedAssistantGroqClient.ProviderException.class)
                    public ResponseEntity<Map<String, Object>> providerUnavailable(GeneratedAssistantGroqClient.ProviderException exception) {
                        boolean rateLimited = exception.statusCode() == 429;
                        long wait = exception.retryAfterSeconds();
                        var response = ResponseEntity.status(rateLimited ? 429 : 503);
                        if (rateLimited) response.header("Retry-After", Long.toString(wait));
                        return response.body(Map.of("error", rateLimited ? "ASSISTANT_RATE_LIMITED" : "ASSISTANT_RUNTIME_UNAVAILABLE",
                                "message", exception.getMessage(), "retryAfterSeconds", rateLimited ? wait : 0L));
                    }
                }
                """, pkg);
    }

    String template(String text, String pkg) { return text.replace("__PACKAGE__", pkg); }
}
