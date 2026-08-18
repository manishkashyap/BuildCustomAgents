package com.manish.customagents.runtime.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BaseAgentResponse(
        String responseId,
        String text,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        Map<String, String> metadata) {

    public BaseAgentResponse {
        responseId = normalizeNullable(responseId);
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = Objects.requireNonNull(finishReason, "finishReason must not be null");
        usage = usage == null ? TokenUsage.unknown() : usage;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentMessage toAssistantMessage() {
        return AgentMessage.assistant(text, toolCalls);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
