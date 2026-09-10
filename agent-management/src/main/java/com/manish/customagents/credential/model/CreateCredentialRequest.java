package com.manish.customagents.credential.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.contracts.ToolAuthRules;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param secret   the material to encrypt. Write-only: no endpoint ever returns it.
 * @param settings non-secret settings the type needs — header name, query parameter, username,
 *                 token URL, scopes. Kept separate from the secret so it can be shown back.
 */
public record CreateCredentialRequest(
        @NotBlank @Size(max = ToolAuthRules.MAX_NAME_LENGTH) String name,
        @NotNull CredentialType type,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 20000) String secret,
        JsonNode settings) {

    @AssertTrue(message = "name may contain only letters, digits, '-', '_' and '.'")
    @JsonIgnore
    public boolean isNameValid() {
        return ToolAuthRules.isValidCredentialName(name);
    }

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
}
