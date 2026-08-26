package com.manish.customagents.contracts;

import java.util.Map;

/**
 * The runtime's answer to "can this agent retire?", shared because the runtime produces it and
 * management consumes it. Null-tolerant on the map so a body missing the field deserialises
 * rather than throwing.
 */
public record RetirementEligibilityResponse(
        boolean eligible,
        long activeRunCount,
        Map<String, Long> activeRunsByStatus) {

    public RetirementEligibilityResponse {
        activeRunsByStatus = activeRunsByStatus == null ? Map.of() : Map.copyOf(activeRunsByStatus);
    }
}
