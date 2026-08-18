package com.manish.customagents.runtime.errors;

public class DraftRevisionConflictException extends RuntimeException {
    public DraftRevisionConflictException(String expected, String actual) {
        super("Draft definition changed since the interaction was generated; expected revision "
                + expected + " but found " + actual);
    }
}
