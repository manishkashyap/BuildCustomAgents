package com.manish.customagents.runtime.tool;

import com.manish.customagents.runtime.errors.AgentExecutionException;

@FunctionalInterface
public interface AgentToolInvocationPort {

    ToolExecutionResult invoke(AgentToolInvocationRequest request);

    static AgentToolInvocationPort unsupported() {
        return request -> {
            throw new AgentExecutionException("Agent-tool invocation is not available in this execution context");
        };
    }
}
