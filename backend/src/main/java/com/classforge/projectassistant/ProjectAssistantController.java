package com.classforge.projectassistant;

import com.classforge.assistant.AssistantPlanningException;
import com.classforge.auth.security.CurrentUser;
import com.classforge.project.application.ProjectNotFoundException;
import com.classforge.project.application.ProjectRevisionConflictException;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/assistant/messages")
public class ProjectAssistantController {
    private final ProjectAssistantService assistant;
    private final CurrentUser user;

    public ProjectAssistantController(ProjectAssistantService assistant, CurrentUser user) {
        this.assistant = assistant; this.user = user;
    }

    @GetMapping
    public ProjectAssistantHistory history(@PathVariable UUID projectId,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) Long before) {
        return assistant.history(user.id(), projectId, before, limit);
    }

    @PostMapping
    public ProjectAssistantResponse send(@PathVariable UUID projectId,
            @Valid @RequestBody ProjectAssistantMessageRequest request) {
        return assistant.send(user.id(), projectId, request);
    }

    @ExceptionHandler(ProjectNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(ProjectNotFoundException error) {
        return Map.of("error", "PROJECT_NOT_FOUND", "message", error.getMessage());
    }

    @ExceptionHandler(ProjectAssistantConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(ProjectAssistantConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "3")
                .body(Map.of("error", "ASSISTANT_REQUEST_CONFLICT", "message", error.getMessage()));
    }

    @ExceptionHandler(ProjectRevisionConflictException.class) @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> revision(ProjectRevisionConflictException error) {
        return Map.of("error", "PROJECT_REVISION_CONFLICT", "message", error.getMessage(),
                "currentRevision", error.getCurrentRevision());
    }

    @ExceptionHandler({ProjectAssistantUnavailableException.class, AssistantPlanningException.class})
    public ResponseEntity<Map<String, Object>> unavailable(RuntimeException error) {
        var providerError = com.classforge.assistant.AssistantProviderErrorResponses.from(error);
        if (providerError != null) return providerError;
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "ASSISTANT_UNAVAILABLE", "message",
                error instanceof ProjectAssistantUnavailableException ? error.getMessage()
                        : "No se pudo preparar la propuesta UML. Puedes reintentar."));
    }
}
