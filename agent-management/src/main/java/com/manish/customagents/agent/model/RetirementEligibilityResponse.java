package com.manish.customagents.agent.model;

import java.util.Map;

public record RetirementEligibilityResponse(
        boolean eligible,
        long activeRunCount,
        Map<String, Long> activeRunsByStatus) {
    public RetirementEligibilityResponse {
        activeRunsByStatus = activeRunsByStatus == null ? Map.of() : Map.copyOf(activeRunsByStatus);
    }
}
