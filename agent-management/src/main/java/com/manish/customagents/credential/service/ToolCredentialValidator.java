package com.manish.customagents.credential.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.credential.entity.TenantCredentialEntity;
import com.manish.customagents.error.InvalidCredentialSettingsException;
import org.springframework.stereotype.Component;

/**
 * Publish-time gate for a tool that references a credential: the credential must exist in this
 * tenant and be active. Checked on publish rather than on save for the same reason as the egress and
 * budget checks — a draft should be writable before its credential exists.
 */
@Component
public class ToolCredentialValidator {

    private final TenantCredentialService credentials;
    private final CredentialReferenceScanner scanner;

    public ToolCredentialValidator(TenantCredentialService credentials, CredentialReferenceScanner scanner) {
        this.credentials = credentials;
        this.scanner = scanner;
    }

    /** @return the referenced credential name, or null when the tool needs no authentication. */
    public String check(String licenseCode, ToolType type, JsonNode configuration) {
        String name = scanner.credentialNameOf(configuration);
        if (name == null) {
            return null;
        }
        if (type != ToolType.HTTP) {
            throw new InvalidCredentialSettingsException(
                    "configuration.auth is only supported on HTTP tools; " + type + " ignores it");
        }
        TenantCredentialEntity credential = credentials.requireActiveByName(licenseCode, name);
        // Re-validate the settings, because a credential can be rotated after a tool references it.
        return credential.getName();
    }
}
