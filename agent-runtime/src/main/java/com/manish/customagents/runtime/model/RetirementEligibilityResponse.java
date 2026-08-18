package com.manish.customagents.runtime.model;

import java.util.Map;

public record RetirementEligibilityResponse(
        boolean eligible,
        long activeRunCount,
        Map<String, Long> activeRunsByStatus) {
    public RetirementEligibilityResponse {
        activeRunsByStatus = Map.copyOf(activeRunsByStatus);
    }
}
