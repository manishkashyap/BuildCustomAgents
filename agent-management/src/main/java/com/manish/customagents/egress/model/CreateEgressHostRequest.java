package com.manish.customagents.egress.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.manish.customagents.contracts.EgressHostRules;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEgressHostRequest(
        @NotBlank @Size(max = EgressHostRules.MAX_PATTERN_LENGTH) String hostPattern,
        @Size(max = 1000) String description) {

    @AssertTrue(message = "hostPattern must be a host name or a *.suffix wildcard covering at least two labels")
    @JsonIgnore
    public boolean isHostPatternValid() {
        return EgressHostRules.isValidPattern(hostPattern);
    }
}
