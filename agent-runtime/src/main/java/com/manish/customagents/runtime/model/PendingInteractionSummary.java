package com.manish.customagents.runtime.model;

import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import java.time.Instant;
import java.util.List;

public record PendingInteractionSummary(
        String interactionId,
        HumanInteractionType type,
        HumanInteractionStatus status,
        String runId,
        String question,
        HumanResponseType responseType,
        HumanAudienceType audienceType,
        List<String> audienceValues,
        String assignedUserId,
        Instant expiresAt) {
}
