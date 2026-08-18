package com.manish.customagents.agent.model;

import com.manish.customagents.agent.enums.AgentStatus;
import java.time.Instant;

public record CustomAgentResponse(
        String id,
        String licenseCode,
        AgentStatus status,
        int version,
        CreateCustomAgentRequest definition,
        Instant createdAt,
        Instant updatedAt) {
}
