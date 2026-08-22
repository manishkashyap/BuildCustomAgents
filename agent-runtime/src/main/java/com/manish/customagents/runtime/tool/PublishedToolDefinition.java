package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.model.ToolDefinition;

import java.util.Objects;
import com.manish.customagents.contracts.ToolType;

public record PublishedToolDefinition(
        String id,
        String name,
        String description,
        ToolType type,
        int version,
        JsonNode inputSchema,
        JsonNode configuration,
        JsonNode executionPolicy) {

    public PublishedToolDefinition {
        id = requireText(id, "tool id");
        name = requireText(name, "tool name");
        description = requireText(description, "tool description");
        type = Objects.requireNonNull(type, "tool type must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("tool version must be greater than zero");
        }
        inputSchema = requireObject(inputSchema, "tool input schema");
        configuration = requireObject(configuration, "tool configuration");
        executionPolicy = executionPolicy == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : requireObject(executionPolicy, "tool execution policy");
    }

    public ToolDefinition toLlmDefinition() {
        return new ToolDefinition(name, description, inputSchema);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    private static JsonNode requireObject(JsonNode value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return value.deepCopy();
    }
}
