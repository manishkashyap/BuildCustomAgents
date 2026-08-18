package com.manish.customagents.runtime.definition;

public class InvalidAgentDefinitionException extends RuntimeException {
    public InvalidAgentDefinitionException(String agentId, Throwable cause) {
        super("Stored definition for custom agent " + agentId + " is invalid", cause);
    }
}
