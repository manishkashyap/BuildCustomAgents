package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RootHumanResponseItem(
        @NotBlank @Size(max = 36) String interactionId,
        @NotNull HumanResponseAction action,
        JsonNode answer,
        @Size(max = 2000) String comment) {

    public SubmitHumanInteractionResponse response() {
        return new SubmitHumanInteractionResponse(action, answer, comment);
    }
}
