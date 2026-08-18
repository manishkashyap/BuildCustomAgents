package com.manish.customagents.runtime.definition;

public class AgentNotFoundException extends RuntimeException {
    public AgentNotFoundException(String agentId) {
        super("Custom agent " + agentId + " was not found");
    }
}
