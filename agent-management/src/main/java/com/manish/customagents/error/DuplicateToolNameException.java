package com.manish.customagents.error;

public class DuplicateToolNameException extends RuntimeException {
    public DuplicateToolNameException(String name) {
        super("An active tool named " + name + " already exists in this tenant");
    }
}
