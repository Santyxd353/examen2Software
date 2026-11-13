package com.classforge.assistant;

import com.classforge.auth.security.CurrentUser;
import com.classforge.assistant.vision.AssistantImagePlanResponse;
import com.classforge.assistant.vision.AssistantImagePlanService;
import com.classforge.assistant.vision.AssistantImageValidationException;
import com.classforge.assistant.vision.VisionModelGatewayException;
import com.classforge.project.application.ProjectRevisionConflictException;
import com.classforge.project.web.ProjectRevisionConflictResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(
        "/api/projects/{projectId}/assistant"
)
public class AssistantController {

    private final AssistantPlanService service;
    private final AssistantVoiceService voiceService;
    private final AssistantImagePlanService imagePlanService;
    private final AssistantRuntimeHealthService runtimeHealthService;
    private final CurrentUser currentUser;

    public AssistantController(
            AssistantPlanService service,
            AssistantVoiceService voiceService,
            AssistantImagePlanService imagePlanService,
            AssistantRuntimeHealthService runtimeHealthService,
            CurrentUser currentUser
    ) {
        this.service =
                service;

        this.voiceService =
                voiceService;

        this.imagePlanService =
                imagePlanService;

        this.runtimeHealthService =
                runtimeHealthService;

        this.currentUser =
                currentUser;
    }

    @GetMapping("/health")
    public AssistantRuntimeHealthResponse health(
            @PathVariable UUID projectId
    ) {
        return runtimeHealthService.health(
                currentUser.id(),
                projectId
        );
    }

    @PostMapping("/plan")
    public AssistantPlanResponse plan(
            @PathVariable UUID projectId,
            @Valid @RequestBody AssistantPlanRequest request
    ) {
        return service.plan(
                currentUser.id(),
                projectId,
                request.text()
        );
    }

    @PostMapping(
            value = "/voice",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public AssistantPlanResponse voice(
            @PathVariable UUID projectId,
            @RequestParam("audio") MultipartFile audio
    ) {
        return voiceService.plan(
                currentUser.id(),
                projectId,
                audio
        );
    }

    @PostMapping(
            value = "/image/plan",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public AssistantImagePlanResponse imagePlan(
            @PathVariable UUID projectId,
            @RequestParam("image") MultipartFile image,
            @RequestParam("baseRevision") long baseRevision
    ) {
        return imagePlanService.plan(
                currentUser.id(),
                projectId,
                baseRevision,
                image
        );
    }

    @ExceptionHandler(
            AssistantPlanningException.class
    )
    public org.springframework.http.ResponseEntity<Map<String, Object>> handle(
            AssistantPlanningException exception
    ) {
        var providerError = AssistantProviderErrorResponses.from(exception);
        if (providerError != null) return providerError;
        Map<String, Object> response =
                new LinkedHashMap<>();

        response.put(
                "error",
                "ASSISTANT_PLANNING_ERROR"
        );

        response.put(
                "message",
                exception.getMessage()
        );

        if (exception.stage() != null) {
            response.put(
                    "stage",
                    exception.stage()
                            .name()
            );
        }

        if (
                exception.source() != null
                        && !exception.source()
                                .isBlank()
        ) {
            response.put(
                    "source",
                    exception.source()
            );
        }

        if (
                exception.transcript() != null
                        && !exception.transcript()
                                .isBlank()
        ) {
            response.put(
                    "transcript",
                    exception.transcript()
            );
        }

        if (
                exception.attemptedPlan()
                        != null
        ) {
            response.put(
                    "attemptedPlan",
                    exception.attemptedPlan()
            );
        }

        Throwable cause = exception.getCause();
        if (cause instanceof VisionModelGatewayException visionException) {
            response.put(
                    "visionReason",
                    visionException.reason().name()
            );
        }

        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
    @ExceptionHandler(AssistantImageValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleImageValidation(
            AssistantImageValidationException exception
    ) {
        return Map.of(
                "error", "IMAGE_INPUT_INVALID",
                "message", exception.getMessage(),
                "stage", "IMAGE_INPUT",
                "source", "IMAGE"
        );
    }

    @ExceptionHandler(ProjectRevisionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProjectRevisionConflictResponse handleRevisionConflict(
            ProjectRevisionConflictException exception
    ) {
        return new ProjectRevisionConflictResponse(
                "PROJECT_REVISION_CONFLICT",
                exception.getMessage(),
                exception.getRequestedRevision(),
                exception.getCurrentRevision()
        );
    }

}
