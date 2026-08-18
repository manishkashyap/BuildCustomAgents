package com.manish.customagents.agent.model;

import com.manish.customagents.agent.enums.AgentStatus;
import java.time.Instant;

public record AgentStatusResponse(
        String id,
        String licenseCode,
        AgentStatus status,
        int version,
        Instant updatedAt) {
}
