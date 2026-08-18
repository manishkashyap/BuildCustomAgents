package com.manish.customagents.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentExample (
        @Size(max = 250) String description,
        @NotNull JsonNode input,
        JsonNode expectedOutput) {
}
