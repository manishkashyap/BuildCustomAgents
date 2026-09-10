package com.manish.customagents.error;

public class DuplicateCredentialNameException extends RuntimeException {
    public DuplicateCredentialNameException(String name) {
        super("Credential name already exists in this tenant: " + name);
    }
}
