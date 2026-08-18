package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

public record ToolExecutionResult(
        JsonNode output,
        Map<String, String> metadata,
        HumanInteractionRequestSpec humanInteraction,
        String waitingChildRunId) {

    public ToolExecutionResult {
        if (output == null && humanInteraction == null && waitingChildRunId == null) {
            throw new IllegalArgumentException("output, humanInteraction, or waitingChildRunId must be provided");
        }
        output = output == null ? null : output.deepCopy();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ToolExecutionResult(JsonNode output, Map<String, String> metadata) {
        this(output, metadata, null, null);
    }

    public static ToolExecutionResult of(JsonNode output) {
        return new ToolExecutionResult(output, Map.of());
    }

    public static ToolExecutionResult waiting(HumanInteractionRequestSpec interaction) {
        return new ToolExecutionResult(null, Map.of(), Objects.requireNonNull(interaction), null);
    }

    public static ToolExecutionResult waitingForChild(String childRunId) {
        return new ToolExecutionResult(null, Map.of(), null, Objects.requireNonNull(childRunId));
    }

    public boolean isWaitingForHuman() {
        return humanInteraction != null;
    }

    public boolean isWaitingForChild() {
        return waitingChildRunId != null;
    }
}
