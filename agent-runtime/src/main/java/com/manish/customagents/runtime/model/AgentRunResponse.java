package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import java.time.Instant;
import java.util.List;

public record AgentRunResponse(
        String runId,
        String rootRunId,
        String agentId,
        int agentVersion,
        AgentRunStatus status,
        String provider,
        String model,
        JsonNode output,
        TokenUsage usage,
        List<PendingInteractionSummary> pendingInteractions,
        Instant startedAt,
        Instant lastActivityAt,
        Instant completedAt) {

    public AgentRunResponse {
        pendingInteractions = pendingInteractions == null ? List.of() : List.copyOf(pendingInteractions);
    }

    public AgentRunResponse(
            String runId, String agentId, int agentVersion, AgentRunStatus status,
            String provider, String model, JsonNode output, TokenUsage usage) {
        this(runId, runId, agentId, agentVersion, status, provider, model, output, usage,
                List.of(), null, null, null);
    }
}
