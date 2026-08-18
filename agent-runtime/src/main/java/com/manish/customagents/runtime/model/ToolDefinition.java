package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ToolDefinition(String name, String description, JsonNode inputSchema) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description must not be blank");
        }
        name = name.strip();
        description = description.strip();
        inputSchema = Objects.requireNonNull(inputSchema, "tool input schema must not be null");
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("tool input schema must be a JSON object");
        }
        inputSchema = inputSchema.deepCopy();
    }
}
