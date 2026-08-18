package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;

public record DraftTestPendingInteraction(
        String interactionId,
        HumanInteractionType type,
        String category,
        String question,
        String reason,
        HumanResponseType responseType,
        JsonNode request,
        String interactionToken) {

    public DraftTestPendingInteraction {
        request = request == null ? null : request.deepCopy();
    }
}
