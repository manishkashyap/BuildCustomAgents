package com.manish.customagents.credential.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.ToolAuthRules;
import com.manish.customagents.credential.enums.CredentialStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * Every field is optional. Supplying {@code secret} rotates it in place, so tools keep working
 * against the same credential name.
 */
public record UpdateCredentialRequest(
        CredentialStatus status,
        @Size(max = 1000) String description,
        @Size(max = 20000) String secret,
        JsonNode settings) {

    @AssertTrue(message = "settings must be a JSON object when provided")
    @JsonIgnore
    public boolean isSettingsValid() {
        return settings == null || settings.isObject();
    }

    @AssertTrue(message = "settings must not contain secret material; put it in the secret field")
    @JsonIgnore
    public boolean isSettingsFreeOfSecrets() {
        if (settings == null || !settings.isObject()) {
            return true;
        }
        for (var entry : settings.properties()) {
            if (ToolAuthRules.looksLikeInlinedSecret(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "rotating a secret requires a non-blank value")
    @JsonIgnore
    public boolean isSecretUsable() {
        return secret == null || !secret.isBlank();
    }
}
