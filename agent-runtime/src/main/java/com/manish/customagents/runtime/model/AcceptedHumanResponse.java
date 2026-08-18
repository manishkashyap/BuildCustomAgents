package com.manish.customagents.runtime.model;

import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import java.time.Instant;

public record AcceptedHumanResponse(
        String interactionId,
        String runId,
        HumanInteractionStatus status,
        HumanResponseAction action,
        String actorId,
        Instant resolvedAt) {
}
