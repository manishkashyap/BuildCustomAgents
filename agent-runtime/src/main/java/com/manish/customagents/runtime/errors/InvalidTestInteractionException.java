package com.manish.customagents.runtime.errors;

public class InvalidTestInteractionException extends RuntimeException {
    public InvalidTestInteractionException(String message) {
        super(message);
    }

    public InvalidTestInteractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
