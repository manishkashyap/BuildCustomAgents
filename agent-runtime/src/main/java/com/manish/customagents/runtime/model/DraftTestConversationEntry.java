package com.manish.customagents.runtime.model;

public record DraftTestConversationEntry(
        String agentId,
        int depth,
        int turn,
        AgentMessage message) {
}
