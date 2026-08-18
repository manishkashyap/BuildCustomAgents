package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

public record ToolCall(
        String id,
        String name,
        JsonNode arguments,
        Map<String, String> providerMetadata) {

    public ToolCall(String id, String name, JsonNode arguments) {
        this(id, name, arguments, Map.of());
    }

    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool call name must not be blank");
        }
        id = id.strip();
        name = name.strip();
        arguments = Objects.requireNonNull(arguments, "tool call arguments must not be null");
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("tool call arguments must be a JSON object");
        }
        arguments = arguments.deepCopy();
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
    }
}
