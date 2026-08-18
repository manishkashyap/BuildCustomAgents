package com.manish.customagents.error;

import com.manish.customagents.agent.enums.AgentStatus;

public class InvalidAgentStatusTransitionException extends RuntimeException {

    public InvalidAgentStatusTransitionException(AgentStatus currentStatus, AgentStatus requestedStatus) {
        super("Agent status cannot transition from " + currentStatus + " to " + requestedStatus);
    }
}
