package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.model.RetirementEligibilityResponse;
import com.manish.customagents.runtime.service.AgentRetirementEligibilityService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(path = "/internal/v1/agents")
public class InternalAgentLifecycleController {
    private final AgentRetirementEligibilityService service;
    private final byte[] expectedToken;

    public InternalAgentLifecycleController(
            AgentRetirementEligibilityService service,
            @Value("${AGENT_INTERNAL_TOKEN:local-internal-token}") String internalToken) {
        this.service = service;
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/{agentId}/retirement-eligibility")
    public ResponseEntity<RetirementEligibilityResponse> retirementEligibility(
            @RequestHeader("X-Agent-License-Code") @NotBlank String licenseCode,
            @RequestHeader("X-Agent-Internal-Token") @NotBlank String internalToken,
            @PathVariable @NotBlank @Size(max = 36) String agentId) {
        if (!MessageDigest.isEqual(expectedToken, internalToken.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Invalid service authentication token");
        }
        return ResponseEntity.ok(service.check(licenseCode, agentId));
    }
}
