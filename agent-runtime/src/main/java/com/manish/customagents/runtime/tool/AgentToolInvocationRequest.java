package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record AgentToolInvocationRequest(
        PublishedToolDefinition tool,
        ToolExecutionContext parentContext,
        String childAgentId,
        int childAgentVersion,
        String task,
        JsonNode input,
        JsonNode settings) {

    public AgentToolInvocationRequest {
        tool = Objects.requireNonNull(tool, "tool must not be null");
        parentContext = Objects.requireNonNull(parentContext, "parentContext must not be null");
        if (childAgentId == null || childAgentId.isBlank()) {
            throw new IllegalArgumentException("childAgentId must not be blank");
        }
        childAgentId = childAgentId.strip();
        if (childAgentVersion <= 0) {
            throw new IllegalArgumentException("childAgentVersion must be greater than zero");
        }
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("child agent task must not be blank");
        }
        task = task.strip();
        input = requireObject(input, "child agent input");
        settings = requireObject(settings, "child agent settings");
    }

    private static JsonNode requireObject(JsonNode value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
        return value.deepCopy();
    }
}
