package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.service.AgentExecutionService;
import com.manish.customagents.runtime.service.AgentRunControlService;
import com.manish.customagents.runtime.model.AddHumanInstructionRequest;
import com.manish.customagents.runtime.model.CancelAgentRunRequest;
import com.manish.customagents.runtime.model.HumanInstructionResponse;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.RunAgentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import java.util.Set;

@Validated
@RestController
@RequestMapping(path = "/api/v1/agent-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentRunController {

    public static final String LICENSE_CODE_HEADER = "X-Agent-License-Code";
    public static final String USER_ID_HEADER = "X-Agent-User-Id";

    private final AgentExecutionService executionService;
    private final AgentRunControlService controlService;

    public AgentRunController(AgentExecutionService executionService, AgentRunControlService controlService) {
        this.executionService = executionService;
        this.controlService = controlService;
    }

    @Operation(
            summary = "Run a published custom agent",
            description = "Loads the tenant's current published definition from the management database and executes it synchronously.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent completed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agent not found", content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Agent is not published", content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "An allowed tool has no runtime implementation", content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Agent execution failed", content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentRunResponse> run(
            @Parameter(description = "platform license code establishing the tenant boundary", required = true)
            @RequestHeader(LICENSE_CODE_HEADER)
            @NotBlank
            @Pattern(
                    regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}",
                    message = "must contain only letters, numbers, dots, underscores, or hyphens")
            String licenseCode,
            @RequestHeader(name = USER_ID_HEADER, defaultValue = "local-requester")
            @NotBlank @jakarta.validation.constraints.Size(max = 128) String requestedBy,
            Authentication authentication,
            @Valid @RequestBody RunAgentRequest request) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, requestedBy, Set.of("RUN_REQUESTER"), licenseCode);
        return ResponseEntity.ok(executionService.run(licenseCode, request, actor.actorId()));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<AgentRunResponse> get(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank String licenseCode,
            Authentication authentication,
            @PathVariable @NotBlank @jakarta.validation.constraints.Size(max = 36) String runId) {
        RuntimeActorContext.resolve(authentication, "local-requester", Set.of("RUN_REQUESTER"), licenseCode);
        return ResponseEntity.ok(executionService.get(licenseCode, runId));
    }

    @PostMapping(path = "/{runId}/instructions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HumanInstructionResponse> addInstruction(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank String licenseCode,
            @PathVariable @NotBlank @jakarta.validation.constraints.Size(max = 36) String runId,
            Authentication authentication,
            @Valid @RequestBody AddHumanInstructionRequest request) {
        RuntimeActorContext.resolve(authentication, "local-requester", Set.of("RUN_OPERATOR"), licenseCode)
                .requireAnyRole("RUN_OPERATOR", "AGENT_ADMIN");
        return ResponseEntity.ok(controlService.addInstruction(licenseCode, runId, request));
    }

    @PostMapping(path = "/{runId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentRunResponse> cancel(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank String licenseCode,
            @PathVariable @NotBlank @jakarta.validation.constraints.Size(max = 36) String runId,
            Authentication authentication,
            @Valid @RequestBody CancelAgentRunRequest request) {
        RuntimeActorContext.resolve(authentication, "local-requester", Set.of("RUN_OPERATOR"), licenseCode)
                .requireAnyRole("RUN_OPERATOR", "AGENT_ADMIN");
        controlService.cancel(licenseCode, runId, request.scope());
        return ResponseEntity.ok(executionService.get(licenseCode, runId));
    }
}
