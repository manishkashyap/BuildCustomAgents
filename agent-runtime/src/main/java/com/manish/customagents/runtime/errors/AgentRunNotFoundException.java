package com.manish.customagents.runtime.errors;

/**
 * An agent run is absent or belongs to another tenant. Both cases report 404 so a caller cannot
 * probe for the existence of runs outside its own license code.
 */
public class AgentRunNotFoundException extends RuntimeException {
    public AgentRunNotFoundException(String message) {
        super(message);
    }
}
