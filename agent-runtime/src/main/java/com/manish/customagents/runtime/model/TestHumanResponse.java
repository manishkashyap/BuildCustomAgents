package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TestHumanResponse(
        @NotBlank @Size(max = 12_000) String interactionToken,
        @NotNull HumanResponseAction action,
        JsonNode answer) {
}
