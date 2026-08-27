package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.ToolInvocationStatus;
import java.time.Instant;
import java.util.List;

/**
 * Read-only turn-by-turn view of a root run and every descendant run it spawned. The published-run
 * counterpart of the conversation a draft test returns inline, assembled from the persisted turn,
 * tool-invocation, and checkpoint rows rather than from live execution state.
 */
public record AgentRunTraceResponse(
        String rootRunId,
        TokenUsage usage,
        int turnCount,
        List<TracedRun> runs) {

    public AgentRunTraceResponse {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /** One run in the root run's tree, at its depth below the root. */
    public record TracedRun(
            String runId,
            String parentRunId,
            String agentId,
            int agentVersion,
            int depth,
            AgentRunStatus status,
            String provider,
            String model,
            JsonNode input,
            JsonNode output,
            String errorMessage,
            TokenUsage usage,
            Instant startedAt,
            Instant lastActivityAt,
            Instant completedAt,
            List<TracedTurn> turns) {

        public TracedRun {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
    }

    /**
     * One generation turn. {@code usage} is the provider usage for this turn alone, so the caller can
     * see where a run's tokens were actually spent instead of only the aggregate.
     *
     * <p>{@code messages} and {@code toolCalls} are the recorded JSON, passed through rather than
     * re-bound to the strict execution records. A trace is a diagnostic view of what actually
     * happened, so one field that no longer satisfies a domain invariant must not blank the turn.
     */
    public record TracedTurn(
            int turnNumber,
            JsonNode messages,
            String text,
            JsonNode toolCalls,
            String finishReason,
            TokenUsage usage,
            Instant createdAt,
            List<TracedToolInvocation> toolInvocations) {

        public TracedTurn {
            toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        }
    }

    /** A tool call dispatched during a turn, including the child run a CUSTOM_AGENT tool started. */
    public record TracedToolInvocation(
            Long invocationId,
            String toolCallId,
            String toolName,
            String toolType,
            Integer toolVersion,
            ToolInvocationStatus status,
            JsonNode arguments,
            JsonNode result,
            String childRunId,
            String errorMessage,
            Long durationMs,
            Instant createdAt) {
    }
}
