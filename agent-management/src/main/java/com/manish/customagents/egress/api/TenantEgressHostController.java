package com.manish.customagents.egress.api;

import com.manish.customagents.agent.api.ManagementActorContext;
import com.manish.customagents.contracts.AgentApiHeaders;
import com.manish.customagents.contracts.LicenseCode;
import com.manish.customagents.egress.model.CreateEgressHostRequest;
import com.manish.customagents.egress.model.EgressHostResponse;
import com.manish.customagents.egress.model.UpdateEgressHostRequest;
import com.manish.customagents.egress.service.TenantEgressHostService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The tenant's HTTP egress allowlist.
 *
 * <p>Writes require an admin role rather than {@code AGENT_EDITOR}: if whoever authors a tool can
 * also approve its destination, the allowlist stops being a control. Reading is open to editors so
 * they can see which hosts are available before writing a tool against one.
 */
@Validated
@RestController
@RequestMapping(path = "/api/v1/egress-hosts", produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantEgressHostController {

    private static final String[] ADMIN_ROLES = {"AGENT_ADMIN", "PLATFORM_ADMIN"};
    private static final String[] READ_ROLES = {
            "AGENT_EDITOR", "AGENT_PUBLISHER", "AGENT_ADMIN", "PLATFORM_ADMIN"};

    private final TenantEgressHostService service;

    public TenantEgressHostController(TenantEgressHostService service) {
        this.service = service;
    }

    @Operation(summary = "List the tenant's allowed HTTP egress hosts")
    @GetMapping
    public ResponseEntity<List<EgressHostResponse>> list(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(AgentApiHeaders.USER_ID) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(AgentApiHeaders.ROLES) @NotBlank String roles,
            Authentication authentication) {
        ManagementActorContext.resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole(READ_ROLES);
        return ResponseEntity.ok(service.list(licenseCode));
    }

    @Operation(summary = "Allow a host for this tenant's HTTP tools")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EgressHostResponse> create(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(AgentApiHeaders.USER_ID) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(AgentApiHeaders.ROLES) @NotBlank String roles,
            Authentication authentication,
            @Valid @RequestBody CreateEgressHostRequest request) {
        ManagementActorContext actor = ManagementActorContext
                .resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole(ADMIN_ROLES);
        EgressHostResponse response = service.create(licenseCode, request, actor.actorId());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(response);
    }

    @Operation(summary = "Enable, disable, or re-describe an allowed host")
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EgressHostResponse> update(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(AgentApiHeaders.USER_ID) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(AgentApiHeaders.ROLES) @NotBlank String roles,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String id,
            @Valid @RequestBody UpdateEgressHostRequest request) {
        ManagementActorContext actor = ManagementActorContext
                .resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole(ADMIN_ROLES);
        return ResponseEntity.ok(service.update(licenseCode, id, request, actor.actorId()));
    }

    @Operation(summary = "Remove an allowed host")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(AgentApiHeaders.USER_ID) @NotBlank @Size(max = 128) String userId,
            @RequestHeader(AgentApiHeaders.ROLES) @NotBlank String roles,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String id) {
        ManagementActorContext.resolve(authentication, userId, roles, licenseCode)
                .requireAnyRole(ADMIN_ROLES);
        service.delete(licenseCode, id);
        return ResponseEntity.noContent().build();
    }
}
