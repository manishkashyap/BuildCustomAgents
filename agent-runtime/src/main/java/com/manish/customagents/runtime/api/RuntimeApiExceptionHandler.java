package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.definition.AgentNotPublishedException;
import com.manish.customagents.runtime.definition.AgentNotDraftException;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import com.manish.customagents.runtime.errors.AgentRunNotFoundException;
import com.manish.customagents.runtime.errors.DraftRevisionConflictException;
import com.manish.customagents.runtime.errors.HumanInteractionConflictException;
import com.manish.customagents.runtime.errors.HumanInteractionNotFoundException;
import com.manish.customagents.runtime.errors.UnsupportedLlmClientException;
import com.manish.customagents.runtime.errors.InvalidTestInteractionException;
import com.manish.customagents.runtime.provider.GenericAgentProviderException;
import com.manish.customagents.runtime.tool.UnsupportedToolTypeException;
import com.manish.customagents.runtime.definition.AgentNotFoundException;
import com.manish.customagents.runtime.definition.AgentRetiringException;
import com.manish.customagents.runtime.tool.PublishedToolNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RuntimeApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException exception) {
        return response(HttpStatus.FORBIDDEN, "Human interaction forbidden", exception.getMessage(),
                "human-interaction-forbidden");
    }

    @ExceptionHandler(AgentNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AgentNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "Agent not found", exception.getMessage(), "agent-not-found");
    }

    @ExceptionHandler(AgentRunNotFoundException.class)
    ResponseEntity<ProblemDetail> handleAgentRunNotFound(AgentRunNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "Agent run not found", exception.getMessage(), "agent-run-not-found");
    }

    @ExceptionHandler(HumanInteractionNotFoundException.class)
    ResponseEntity<ProblemDetail> interactionNotFound(HumanInteractionNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "Human interaction not found", exception.getMessage(),
                "human-interaction-not-found");
    }

    @ExceptionHandler(HumanInteractionConflictException.class)
    ResponseEntity<ProblemDetail> interactionConflict(HumanInteractionConflictException exception) {
        HttpStatus status = switch (exception.problemType()) {
            case "invalid-human-interaction-response" -> HttpStatus.BAD_REQUEST;
            case "human-interaction-limit-exceeded" -> HttpStatus.TOO_MANY_REQUESTS;
            case "agent-run-not-found" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return response(status, "Human interaction cannot be resolved", exception.getMessage(),
                exception.problemType());
    }

    @ExceptionHandler(AgentNotPublishedException.class)
    ResponseEntity<ProblemDetail> notPublished(AgentNotPublishedException exception) {
        return response(HttpStatus.CONFLICT, "Agent is not published", exception.getMessage(), "agent-not-published");
    }

    @ExceptionHandler(AgentNotDraftException.class)
    ResponseEntity<ProblemDetail> notDraft(AgentNotDraftException exception) {
        return response(HttpStatus.CONFLICT, "Agent is not draft", exception.getMessage(), "agent-not-draft");
    }

    @ExceptionHandler(DraftRevisionConflictException.class)
    ResponseEntity<ProblemDetail> draftChanged(DraftRevisionConflictException exception) {
        return response(HttpStatus.CONFLICT, "Draft definition changed", exception.getMessage(),
                "draft-definition-changed");
    }

    @ExceptionHandler(InvalidTestInteractionException.class)
    ResponseEntity<ProblemDetail> invalidTestInteraction(InvalidTestInteractionException exception) {
        return response(HttpStatus.BAD_REQUEST, "Invalid draft test interaction", exception.getMessage(),
                "invalid-draft-test-interaction");
    }

    @ExceptionHandler(AgentRetiringException.class)
    ResponseEntity<ProblemDetail> retiring(AgentRetiringException exception) {
        return response(HttpStatus.CONFLICT, "Agent is retiring", exception.getMessage(), "agent-retiring");
    }

    @ExceptionHandler({PublishedToolNotFoundException.class, UnsupportedToolTypeException.class})
    ResponseEntity<ProblemDetail> unavailableTool(RuntimeException exception) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "Dynamic tool unavailable", exception.getMessage(), "dynamic-tool-unavailable");
    }

    @ExceptionHandler(UnsupportedLlmClientException.class)
    ResponseEntity<ProblemDetail> unsupportedProvider(UnsupportedLlmClientException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "LLM adapter unavailable", exception.getMessage(), "llm-adapter-unavailable");
    }

    @ExceptionHandler({GenericAgentProviderException.class, AgentExecutionException.class})
    ResponseEntity<ProblemDetail> executionFailed(RuntimeException exception) {
        return response(HttpStatus.BAD_GATEWAY, "Agent execution failed", exception.getMessage(), "agent-execution-failed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException exception) {
        List<Map<String, String>> violations = exception.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid run request",
                "One or more request fields are invalid", "invalid-run-request");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> invalidParameter(ConstraintViolationException exception) {
        List<Map<String, String>> violations = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "message", violation.getMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "A request header or parameter is invalid", "invalid-request");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedJson() {
        return response(HttpStatus.BAD_REQUEST, "Malformed JSON request",
                "The request body is missing or is not valid JSON", "malformed-json");
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status, String title, String detail, String type) {
        return ResponseEntity.status(status).body(problem(status, title, detail, type));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://agent-platform.example/problems/" + type));
        return problem;
    }
}
