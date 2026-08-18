package com.manish.customagents.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.agent.model.AgentStatusResponse;
import com.manish.customagents.agent.model.CopyCustomAgentRequest;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.agent.model.CustomAgentResponse;
import com.manish.customagents.agent.model.UpdateAgentStatusRequest;
import com.manish.customagents.agent.service.CustomAgentService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Validated
@RestController
@RequestMapping(path = "/api/v1/agents", produces = MediaType.APPLICATION_JSON_VALUE)
public class CustomAgentController {
    public static final String LICENSE_CODE_HEADER = "X-Agent-License-Code";
    public static final String USER_ID_HEADER = "X-Agent-User-Id";
    public static final String ROLES_HEADER = "X-Agent-Roles";
    public static final String CHANGE_REASON_HEADER = "X-Agent-Change-Reason";

    private final CustomAgentService service;

    public CustomAgentController(CustomAgentService service) { this.service = service; }

    @Operation(summary = "Create a custom agent")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomAgentResponse> createAgent(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            @RequestHeader(name = CHANGE_REASON_HEADER, required = false) @Size(max = 1000) String reason,
            Authentication authentication,
            @Valid @RequestBody CreateCustomAgentRequest request) {
        ManagementActorContext actor = editor(authentication, userId, roles, licenseCode);
        CustomAgentResponse response = service.create(
                licenseCode, request, actor.actorId(), reason);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{agentId}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location.toString()).body(response);
    }

    @Operation(summary = "List custom agents")
    @GetMapping
    public ResponseEntity<List<CustomAgentResponse>> listAgents(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            Authentication authentication) {
        reader(authentication, userId, roles, licenseCode);
        return ResponseEntity.ok(service.list(licenseCode));
    }

    @Operation(summary = "Get a custom agent")
    @GetMapping("/{agentId}")
    public ResponseEntity<CustomAgentResponse> getAgent(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String agentId) {
        reader(authentication, userId, roles, licenseCode);
        return ResponseEntity.ok(service.get(licenseCode, agentId));
    }

    @Operation(summary = "Update a draft custom agent",
            description = "Applies JSON merge-style partial updates to a DRAFT agent."
                    + " Published, retiring, and retired agents are immutable.")
    @PatchMapping(path = "/{agentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomAgentResponse> updateAgent(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            @RequestHeader(name = CHANGE_REASON_HEADER, required = false) @Size(max = 1000) String reason,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String agentId,
            @RequestBody JsonNode patch) {
        ManagementActorContext actor = editor(authentication, userId, roles, licenseCode);
        return ResponseEntity.ok(service.updateDraft(
                licenseCode, agentId, patch, actor.actorId(), reason));
    }

    @Operation(summary = "Copy a published or retired custom agent")
    @PostMapping(path = "/{agentId}/copies", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomAgentResponse> copyAgent(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String idempotencyKey,
            @RequestHeader(name = CHANGE_REASON_HEADER, required = false) @Size(max = 1000) String reason,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String agentId,
            @Valid @RequestBody CopyCustomAgentRequest request) {
        ManagementActorContext actor = editor(authentication, userId, roles, licenseCode);
        CustomAgentResponse response = service.copy(
                licenseCode, agentId, request, idempotencyKey, actor.actorId(), reason);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/agents/{agentId}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location.toString()).body(response);
    }

    @Operation(summary = "Publish or retire a custom agent")
    @PatchMapping(path = "/{agentId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentStatusResponse> updateStatus(
            @RequestHeader(LICENSE_CODE_HEADER) @NotBlank
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String licenseCode,
            @RequestHeader(USER_ID_HEADER) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(ROLES_HEADER) @NotBlank String roles,
            @RequestHeader(name = CHANGE_REASON_HEADER, required = false) @Size(max = 1000) String reason,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String agentId,
            @Valid @RequestBody UpdateAgentStatusRequest request) {
        ManagementActorContext actor = ManagementActorContext.resolve(
                authentication, userId, roles, licenseCode).requireAnyRole(
                        "AGENT_PUBLISHER", "AGENT_ADMIN", "PLATFORM_ADMIN");
        return ResponseEntity.ok(service.updateStatus(
                licenseCode, agentId, request.status(), actor.actorId(), reason));
    }

    private ManagementActorContext editor(Authentication authentication, String userId,
            String roles, String licenseCode) {
        return ManagementActorContext.resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole("AGENT_EDITOR", "AGENT_ADMIN", "PLATFORM_ADMIN");
    }

    private ManagementActorContext reader(Authentication authentication, String userId,
            String roles, String licenseCode) {
        return ManagementActorContext.resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole("AGENT_EDITOR", "AGENT_PUBLISHER", "AGENT_ADMIN", "PLATFORM_ADMIN");
    }

}
