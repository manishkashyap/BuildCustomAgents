package com.manish.customagents.runtime.model;

import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import java.time.Instant;

public record HumanInteractionResolutionResponse(
        String interactionId,
        HumanInteractionStatus status,
        HumanResponseAction action,
        String actorId,
        Instant resolvedAt,
        String rootRunId,
        AgentRunStatus runStatus) {
}
