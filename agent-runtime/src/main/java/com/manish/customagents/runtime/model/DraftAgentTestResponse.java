package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record DraftAgentTestResponse(
        String testRunId,
        String agentId,
        String draftRevision,
        DraftAgentTestStatus status,
        String provider,
        String model,
        JsonNode output,
        TokenUsage usage,
        List<DraftTestPendingInteraction> pendingInteractions,
        List<MockedToolCall> mockedToolCalls,
        List<DraftTestConversationEntry> conversation,
        Instant startedAt,
        Instant completedAt) {

    public DraftAgentTestResponse {
        output = output == null ? null : output.deepCopy();
        pendingInteractions = pendingInteractions == null ? List.of() : List.copyOf(pendingInteractions);
        mockedToolCalls = mockedToolCalls == null ? List.of() : List.copyOf(mockedToolCalls);
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
    }
}
