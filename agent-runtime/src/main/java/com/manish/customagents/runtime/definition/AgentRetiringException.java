package com.manish.customagents.runtime.definition;

public class AgentRetiringException extends RuntimeException {
    public AgentRetiringException(String agentId) {
        super("Custom agent " + agentId + " is retiring and cannot accept new runs");
    }
}
