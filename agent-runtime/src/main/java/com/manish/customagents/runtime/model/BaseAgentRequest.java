package com.manish.customagents.runtime.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BaseAgentRequest(
        ModelSelection model,
        List<AgentMessage> messages,
        List<ToolDefinition> tools,
        GenerationOptions options,
        Map<String, String> metadata) {

    public BaseAgentRequest {
        model = Objects.requireNonNull(model, "model must not be null");
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("at least one message is required");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        options = options == null ? GenerationOptions.defaults() : options;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
