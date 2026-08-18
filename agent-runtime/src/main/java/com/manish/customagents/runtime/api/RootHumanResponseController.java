package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.model.RootHumanResponsesResponse;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import com.manish.customagents.runtime.service.RootHumanResponseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(path = "/api/v1/agent-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RootHumanResponseController {
    private static final String ROLES = "X-Agent-Roles";
    private final RootHumanResponseService service;

    public RootHumanResponseController(RootHumanResponseService service) {
        this.service = service;
    }

    @Operation(
            summary = "Answer one or more root-run human interactions",
            description = "Atomically persists the response batch, synchronously resumes affected runs "
                    + "deepest-first, and returns when the root reaches its next stable state.")
    @PostMapping(path = "/{rootRunId}/human-responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RootHumanResponsesResponse> respond(
            @RequestHeader(AgentRunController.LICENSE_CODE_HEADER) @NotBlank String licenseCode,
            @RequestHeader(name = AgentRunController.USER_ID_HEADER, defaultValue = "local-requester")
            @NotBlank @Size(max = 128) String actorId,
            @RequestHeader(name = ROLES, defaultValue = "RUN_REQUESTER") String roles,
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @PathVariable @NotBlank @Size(max = 36) String rootRunId,
            @Valid @RequestBody SubmitRootHumanResponsesRequest command) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, actorId, roles(roles), licenseCode);
        return ResponseEntity.ok(service.respond(
                licenseCode, rootRunId, idempotencyKey,
                actor.actorId(), actor.roles(), command));
    }

    private Set<String> roles(String header) {
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
