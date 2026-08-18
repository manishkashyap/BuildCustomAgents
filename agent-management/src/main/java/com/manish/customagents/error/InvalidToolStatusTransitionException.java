package com.manish.customagents.error;

import com.manish.customagents.tool.enums.ToolStatus;

public class InvalidToolStatusTransitionException extends RuntimeException {
    public InvalidToolStatusTransitionException(ToolStatus current, ToolStatus requested) {
        super("Tool status cannot transition from " + current + " to " + requested);
    }
}
