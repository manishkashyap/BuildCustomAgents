package com.manish.customagents.runtime.definition;

public class AgentNotDraftException extends RuntimeException {
    public AgentNotDraftException(String agentId, String status) {
        super("Custom agent " + agentId + " cannot be tested because its status is " + status);
    }
}
