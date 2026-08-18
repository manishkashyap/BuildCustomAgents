package com.manish.customagents.runtime.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CancelAgentRunRequest(
        @NotBlank @Pattern(regexp = "ROOT|THIS_RUN") String scope,
        @NotBlank @Size(max = 2000) String reason) {
}
