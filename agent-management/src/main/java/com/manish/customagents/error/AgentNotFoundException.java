package com.manish.customagents.error;

public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String agentId) {
        super("No active custom agent with ID '" + agentId + "' exists in this tenant");
    }
}
