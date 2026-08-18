package com.manish.customagents.runtime.errors;

public class HumanInteractionConflictException extends RuntimeException {
    private final String problemType;

    public HumanInteractionConflictException(String problemType, String message) {
        super(message);
        this.problemType = problemType;
    }

    public String problemType() {
        return problemType;
    }
}
