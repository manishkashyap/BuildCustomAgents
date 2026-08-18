package com.manish.customagents.runtime.definition;

public class AgentNotPublishedException extends RuntimeException {
    public AgentNotPublishedException(String agentId, String status) {
        super("Custom agent " + agentId + " cannot run because its status is " + status);
    }
}
