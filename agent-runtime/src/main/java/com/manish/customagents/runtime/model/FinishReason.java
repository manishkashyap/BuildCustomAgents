package com.manish.customagents.runtime.model;

public enum FinishReason {
    STOP,
    TOOL_CALLS,
    MAX_TOKENS,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN
}
