package com.manish.customagents.agent.model;

import com.manish.customagents.agent.enums.AgentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;

public record UpdateAgentStatusRequest(@NotNull AgentStatus status) {
    @AssertTrue(message = "RETIRING is an internal status and cannot be requested")
    public boolean isPublicStatus() { return status == null || status != AgentStatus.RETIRING; }
}
