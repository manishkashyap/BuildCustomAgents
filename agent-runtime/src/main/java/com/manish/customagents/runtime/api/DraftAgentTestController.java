package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.model.DraftAgentTestRequest;
import com.manish.customagents.runtime.model.DraftAgentTestResponse;
import com.manish.customagents.runtime.service.DraftAgentTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.manish.customagents.contracts.AgentApiHeaders;
import com.manish.customagents.contracts.LicenseCode;

@Validated
@RestController
@RequestMapping(path = "/api/v1/agent-test-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class DraftAgentTestController {


    private final DraftAgentTestService testService;

    public DraftAgentTestController(DraftAgentTestService testService) {
        this.testService = testService;
    }

    @Operation(
            summary = "Test a draft custom agent",
            description = "Executes a DRAFT agent synchronously without persisting runtime state. "
                    + "Only published tools and published child agents are available; side-effecting tools are mocked.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Test completed or needs human input"),
            @ApiResponse(responseCode = "400", description = "Invalid test request or interaction token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Caller cannot edit agents",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agent not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Agent is not draft or draft revision changed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "A required published tool is unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Test execution failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DraftAgentTestResponse> test(
            @Parameter(description = "Tenant license code", required = true)
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode,
            @RequestHeader(name = AgentApiHeaders.USER_ID, defaultValue = "local-editor")
            @NotBlank @Size(max = 128) String requestedBy,
            Authentication authentication,
            @Valid @RequestBody DraftAgentTestRequest request) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, requestedBy, Set.of("AGENT_EDITOR"), licenseCode)
                .requireAnyRole("AGENT_EDITOR", "AGENT_ADMIN");
        return ResponseEntity.ok(testService.test(licenseCode, request, actor.actorId()));
    }
}
