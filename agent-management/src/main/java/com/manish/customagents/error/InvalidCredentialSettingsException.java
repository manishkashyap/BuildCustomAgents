package com.manish.customagents.error;

/** The non-secret settings do not carry what the credential type needs to work. */
public class InvalidCredentialSettingsException extends RuntimeException {
    public InvalidCredentialSettingsException(String message) {
        super(message);
    }
}
