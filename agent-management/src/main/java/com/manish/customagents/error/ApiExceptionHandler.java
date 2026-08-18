package com.manish.customagents.error;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import com.manish.customagents.agent.service.InvalidAgentDefinitionRequestException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleForbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(
                HttpStatus.FORBIDDEN, "Agent operation forbidden", exception.getMessage(),
                "agent-operation-forbidden"));
    }

    @ExceptionHandler(InvalidAgentDefinitionRequestException.class)
    ResponseEntity<ProblemDetail> handleInvalidAgentPatch(
            InvalidAgentDefinitionRequestException exception) {
        return ResponseEntity.badRequest().body(problem(
                HttpStatus.BAD_REQUEST, "Invalid custom agent definition",
                exception.getMessage(), "invalid-agent-definition"));
    }

    @ExceptionHandler(AgentCopyConflictException.class)
    ResponseEntity<ProblemDetail> handleCopyConflict(AgentCopyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, "Agent copy conflict", exception.getMessage(),
                "agent-copy-conflict"));
    }

    @ExceptionHandler(AgentDependencyException.class)
    ResponseEntity<ProblemDetail> handleAgentDependency(AgentDependencyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, "Agent dependency is unavailable", exception.getMessage(),
                "agent-dependency-unavailable"));
    }

    @ExceptionHandler(AgentRetirementBlockedException.class)
    ResponseEntity<ProblemDetail> handleRetirementBlocked(AgentRetirementBlockedException exception) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Agent retirement blocked",
                exception.getMessage(), "agent-retirement-blocked");
        problem.setProperty("activeRunCount", exception.activeRunCount());
        problem.setProperty("activeRunsByStatus", exception.activeRunsByStatus());
        problem.setProperty("dependentAgents", exception.dependentAgents());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(RuntimeRetirementCheckException.class)
    ResponseEntity<ProblemDetail> handleRuntimeUnavailable(RuntimeRetirementCheckException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem(
                HttpStatus.SERVICE_UNAVAILABLE, "Agent Runtime unavailable",
                exception.getMessage(), "runtime-retirement-check-unavailable"));
    }

    @ExceptionHandler(ToolNotFoundException.class)
    ResponseEntity<ProblemDetail> handleToolNotFound(ToolNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(
                HttpStatus.NOT_FOUND, "Tool not found", exception.getMessage(), "tool-not-found"));
    }

    @ExceptionHandler(InvalidToolStatusTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidToolStatusTransition(
            InvalidToolStatusTransitionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, "Invalid tool status transition",
                exception.getMessage(), "invalid-tool-status-transition"));
    }

    @ExceptionHandler(DuplicateToolNameException.class)
    ResponseEntity<ProblemDetail> handleDuplicateToolName(DuplicateToolNameException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT, "Tool name already exists",
                exception.getMessage(), "tool-name-conflict"));
    }

    @ExceptionHandler(AgentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleAgentNotFound(AgentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(
                HttpStatus.NOT_FOUND,
                "Agent not found",
                exception.getMessage(),
                "agent-not-found"));
    }

    @ExceptionHandler(InvalidAgentStatusTransitionException.class)
    ResponseEntity<ProblemDetail> handleInvalidStatusTransition(
            InvalidAgentStatusTransitionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(
                HttpStatus.CONFLICT,
                "Invalid agent status transition",
                exception.getMessage(),
                "invalid-agent-status-transition"));
    }

    @ExceptionHandler(DuplicateAgentNameException.class)
    ResponseEntity<ProblemDetail> handleDuplicateAgentName(DuplicateAgentNameException exception) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "Agent name already exists",
                exception.getMessage(),
                "agent-name-conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(MethodArgumentNotValidException exception) {
        List<Map<String, String>> violations = exception.getBindingResult().getAllErrors().stream()
                .map(error -> Map.of(
                        "field", error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid custom agent definition",
                "One or more request fields are invalid",
                "invalid-agent-definition");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        List<Map<String, String>> violations = exception.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "message", violation.getMessage()))
                .toList();

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request",
                "A request header or parameter is invalid",
                "invalid-request");
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableMessage() {
        return ResponseEntity.badRequest().body(problem(
                HttpStatus.BAD_REQUEST,
                "Malformed JSON request",
                "The request body is missing or is not valid JSON",
                "malformed-json"));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://agent-platform.example/problems/" + type));
        return problem;
    }
}
