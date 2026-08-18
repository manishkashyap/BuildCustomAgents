package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitHumanInteractionResponse(
        @NotNull HumanResponseAction action,
        JsonNode answer,
        @Size(max = 2000) String comment) {
}
