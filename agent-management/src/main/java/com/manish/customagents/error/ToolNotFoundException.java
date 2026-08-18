package com.manish.customagents.error;

public class ToolNotFoundException extends RuntimeException {
    public ToolNotFoundException(String toolId) {
        super("Tool " + toolId + " was not found");
    }
}
