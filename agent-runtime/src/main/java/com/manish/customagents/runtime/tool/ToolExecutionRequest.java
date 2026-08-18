package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public record ToolExecutionRequest(
        PublishedToolDefinition tool,
        ToolExecutionContext context,
        JsonNode arguments) {

    public ToolExecutionRequest {
        tool = Objects.requireNonNull(tool, "tool must not be null");
        context = Objects.requireNonNull(context, "context must not be null");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null").deepCopy();
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("tool arguments must be a JSON object");
        }
    }
}
