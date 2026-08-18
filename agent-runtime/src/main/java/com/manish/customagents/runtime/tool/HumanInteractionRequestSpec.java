package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import java.time.Duration;
import java.util.List;

public record HumanInteractionRequestSpec(
        HumanInteractionType type,
        String category,
        String question,
        String reason,
        HumanResponseType responseType,
        JsonNode request,
        HumanAudienceType audienceHint,
        List<String> audienceValues,
        Duration expiresAfter,
        int maxRequestsPerRootRun) {

    public HumanInteractionRequestSpec {
        audienceValues = audienceValues == null ? List.of() : List.copyOf(audienceValues);
        expiresAfter = expiresAfter == null ? Duration.ofHours(24) : expiresAfter;
        if (maxRequestsPerRootRun < 1) maxRequestsPerRootRun = 20;
    }

    public HumanInteractionRequestSpec(
            HumanInteractionType type,
            String category,
            String question,
            String reason,
            HumanResponseType responseType,
            JsonNode request,
            HumanAudienceType audienceHint,
            List<String> audienceValues,
            Duration expiresAfter) {
        this(type, category, question, reason, responseType, request, audienceHint,
                audienceValues, expiresAfter, 20);
    }
}
