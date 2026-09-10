package com.manish.customagents.error;

public class CredentialNotFoundException extends RuntimeException {
    public CredentialNotFoundException(String reference) {
        super("Credential not found: " + reference);
    }
}
