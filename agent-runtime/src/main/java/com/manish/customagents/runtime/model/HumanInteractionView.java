package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import java.time.Instant;
import java.util.List;

public record HumanInteractionView(
        String interactionId,
        String rootRunId,
        String runId,
        HumanInteractionType type,
        HumanInteractionStatus status,
        String category,
        String question,
        String reason,
        HumanResponseType responseType,
        JsonNode request,
        HumanAudienceType audienceType,
        List<String> audienceValues,
        String assignedUserId,
        Instant createdAt,
        Instant expiresAt,
        Instant resolvedAt) {
}
