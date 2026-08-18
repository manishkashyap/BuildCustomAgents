package com.manish.customagents.runtime.model;

import java.util.List;
import java.util.Objects;

public record AgentMessage(
        MessageRole role,
        String content,
        String name,
        String toolCallId,
        List<AttachmentReference> attachments,
        List<ToolCall> toolCalls) {

    public AgentMessage {
        role = Objects.requireNonNull(role, "message role must not be null");
        content = content == null ? "" : content;
        name = normalizeNullable(name);
        toolCallId = normalizeNullable(toolCallId);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);

        if (role == MessageRole.TOOL) {
            if (toolCallId == null || name == null) {
                throw new IllegalArgumentException("tool messages require name and toolCallId");
            }
            if (!attachments.isEmpty() || !toolCalls.isEmpty()) {
                throw new IllegalArgumentException("tool messages cannot contain attachments or tool calls");
            }
        } else if (toolCallId != null) {
            throw new IllegalArgumentException("toolCallId is valid only for tool messages");
        }

        if (role != MessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages can contain tool calls");
        }
        if (role != MessageRole.USER && !attachments.isEmpty()) {
            throw new IllegalArgumentException("only user messages can contain attachments");
        }
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(MessageRole.SYSTEM, content, null, null, List.of(), List.of());
    }

    public static AgentMessage user(String content) {
        return user(content, List.of());
    }

    public static AgentMessage user(String content, List<AttachmentReference> attachments) {
        return new AgentMessage(MessageRole.USER, content, null, null, attachments, List.of());
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage(MessageRole.ASSISTANT, content, null, null, List.of(), toolCalls);
    }

    public static AgentMessage toolResult(String toolCallId, String toolName, String result) {
        return new AgentMessage(MessageRole.TOOL, result, toolName, toolCallId, List.of(), List.of());
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
