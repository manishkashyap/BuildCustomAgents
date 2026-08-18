package com.manish.customagents.runtime.tool;

/** Executes dynamically loaded tool definitions of one transport or execution type. */
public interface ToolExecutor {
    ToolType type();
    ToolExecutionResult execute(ToolExecutionRequest request);
}
