package com.manish.customagents.runtime.enums;

public enum ToolInvocationStatus {
    PENDING,
    WAITING_FOR_APPROVAL,
    APPROVED,
    RUNNING,
    WAITING_FOR_HUMAN,
    WAITING_FOR_CHILD,
    SUCCEEDED,
    FAILED,
    REJECTED,
    CANCELLED,
    UNKNOWN_OUTCOME
}
