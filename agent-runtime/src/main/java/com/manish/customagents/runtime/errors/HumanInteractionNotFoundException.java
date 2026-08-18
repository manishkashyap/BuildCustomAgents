package com.manish.customagents.runtime.errors;

public class HumanInteractionNotFoundException extends RuntimeException {
    public HumanInteractionNotFoundException(String id) {
        super("Human interaction not found: " + id);
    }
}
