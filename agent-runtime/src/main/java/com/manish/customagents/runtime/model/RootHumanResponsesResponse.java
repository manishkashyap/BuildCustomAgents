package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import java.time.Instant;
import java.util.List;

public record RootHumanResponsesResponse(
        String batchId,
        String rootRunId,
        AgentRunStatus rootRunStatus,
        List<AcceptedHumanResponse> acceptedResponses,
        int pendingInteractionCount,
        List<PendingInteractionSummary> pendingInteractions,
        Instant acceptedAt,
        JsonNode output) {

    public RootHumanResponsesResponse {
        acceptedResponses = acceptedResponses == null ? List.of() : List.copyOf(acceptedResponses);
        pendingInteractions = pendingInteractions == null ? List.of() : List.copyOf(pendingInteractions);
    }

    public RootHumanResponsesResponse(
            String batchId,
            String rootRunId,
            AgentRunStatus rootRunStatus,
            List<AcceptedHumanResponse> acceptedResponses,
            int pendingInteractionCount,
            List<PendingInteractionSummary> pendingInteractions,
            Instant acceptedAt) {
        this(batchId, rootRunId, rootRunStatus, acceptedResponses, pendingInteractionCount,
                pendingInteractions, acceptedAt, null);
    }
}
