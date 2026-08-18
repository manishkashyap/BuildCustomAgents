package com.manish.customagents.runtime.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddHumanInstructionRequest(
        @NotBlank @Size(max = 20_000) String message,
        @Size(max = 36) String targetRunId) {
}
