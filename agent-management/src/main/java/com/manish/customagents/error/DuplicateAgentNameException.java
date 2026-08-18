package com.manish.customagents.error;

public class DuplicateAgentNameException extends RuntimeException {

    public DuplicateAgentNameException(String agentName) {
        super("An active custom agent named '%s' already exists in this tenant".formatted(agentName));
    }
}
