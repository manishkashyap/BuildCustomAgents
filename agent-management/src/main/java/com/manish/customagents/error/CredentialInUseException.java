package com.manish.customagents.error;

import java.util.List;

/** A credential cannot be deleted while a non-deleted tool still references it. */
public class CredentialInUseException extends RuntimeException {

    private final List<String> tools;

    public CredentialInUseException(String name, List<String> tools) {
        super("Credential " + name + " is still referenced by: " + String.join(", ", tools));
        this.tools = List.copyOf(tools);
    }

    public List<String> tools() {
        return tools;
    }
}
