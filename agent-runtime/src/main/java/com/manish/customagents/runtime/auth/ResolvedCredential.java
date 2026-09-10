package com.manish.customagents.runtime.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.CredentialType;

/**
 * A credential with its secret decrypted, held only for the duration of one tool call.
 *
 * <p>Never logged, never serialised, and never written to an invocation record. {@link #toString()}
 * is overridden because the default record implementation would print the secret into any log line
 * or exception message that happened to include the object.
 */
public record ResolvedCredential(
        String id,
        String name,
        CredentialType type,
        String secret,
        JsonNode settings) {

    /** A setting with surrounding whitespace removed: right for names, URLs and identifiers. */
    public String setting(String key) {
        return settings == null ? "" : settings.path(key).asText("").strip();
    }

    /**
     * A setting exactly as configured. Needed where trailing whitespace is significant — a
     * {@code valuePrefix} of {@code "Token "} must keep its separator, or the header comes out as
     * {@code Tokenabc} and the API rejects it.
     */
    public String rawSetting(String key) {
        return settings == null ? "" : settings.path(key).asText("");
    }

    @Override
    public String toString() {
        return "ResolvedCredential[name=" + name + ", type=" + type + ", secret=<redacted>]";
    }
}
