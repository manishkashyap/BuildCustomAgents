package com.manish.customagents.credential.api;

import com.manish.customagents.contracts.AgentApiHeaders;
import com.manish.customagents.contracts.LicenseCode;
import com.manish.customagents.credential.model.CreateCredentialRequest;
import com.manish.customagents.credential.model.CredentialResponse;
import com.manish.customagents.credential.model.UpdateCredentialRequest;
import com.manish.customagents.credential.service.TenantCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Credentials an HTTP tool can present.
 *
 * <p>Open to any role that can author a tool, matching the tool endpoints, and scoped to one tenant.
 * That is safe only because no response here carries the secret: a credential can be created,
 * rotated and attached, but never read back. What stops it being sent somewhere it should not go is
 * the tenant egress allowlist, which is administered separately.
 */
@Validated
@RestController
@RequestMapping(path = "/api/v1/credentials", produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantCredentialController {

    private final TenantCredentialService service;

    public TenantCredentialController(TenantCredentialService service) {
        this.service = service;
    }

    @Operation(summary = "List the tenant's credentials, without secrets")
    @GetMapping
    public ResponseEntity<List<CredentialResponse>> list(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode) {
        return ResponseEntity.ok(service.list(licenseCode));
    }

    @Operation(summary = "Get one credential, without its secret")
    @GetMapping("/{id}")
    public ResponseEntity<CredentialResponse> get(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @PathVariable @NotBlank @Size(max = 36) String id) {
        return ResponseEntity.ok(service.get(licenseCode, id));
    }

    @Operation(summary = "Store a credential; the secret is encrypted and never returned")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CredentialResponse> create(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(name = AgentApiHeaders.USER_ID, defaultValue = "local-user")
            @NotBlank @Size(max = 128) String userId,
            @Valid @RequestBody CreateCredentialRequest request) {
        CredentialResponse response = service.create(licenseCode, request, userId);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(response);
    }

    @Operation(summary = "Rotate a secret, or enable, disable or re-describe a credential")
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CredentialResponse> update(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(name = AgentApiHeaders.USER_ID, defaultValue = "local-user")
            @NotBlank @Size(max = 128) String userId,
            @PathVariable @NotBlank @Size(max = 36) String id,
            @Valid @RequestBody UpdateCredentialRequest request) {
        return ResponseEntity.ok(service.update(licenseCode, id, request, userId));
    }

    @Operation(summary = "Delete a credential no tool references")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE) @LicenseCode String licenseCode,
            @RequestHeader(name = AgentApiHeaders.USER_ID, defaultValue = "local-user")
            @NotBlank @Size(max = 128) String userId,
            @PathVariable @NotBlank @Size(max = 36) String id) {
        service.delete(licenseCode, id, userId);
        return ResponseEntity.noContent().build();
    }
}
