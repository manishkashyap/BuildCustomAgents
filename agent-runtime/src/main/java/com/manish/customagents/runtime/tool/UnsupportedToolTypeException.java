package com.manish.customagents.runtime.tool;

public class UnsupportedToolTypeException extends RuntimeException {
    public UnsupportedToolTypeException(ToolType type) {
        super("No ToolExecutor is registered for published tool type " + type);
    }
}
