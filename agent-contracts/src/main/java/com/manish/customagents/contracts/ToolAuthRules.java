package com.manish.customagents.contracts;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Rules for the {@code configuration.auth} block on an HTTP tool, shared so Management validates
 * exactly what Runtime will later read.
 *
 * <p>The single invariant worth stating plainly: a tool's {@code configuration} is returned verbatim
 * by {@code GET /api/v1/tools} and rendered in the console, so it may reference a credential by name
 * but must never contain secret material. Everything here exists to enforce that.
 *
 * <p>Auth also belongs in configuration rather than {@code inputSchema}. Only the name, description
 * and input schema reach the model; putting a credential field in the input schema would show it to
 * the model and invite it to fill one in.
 */
public final class ToolAuthRules {

    /** The only key an author supplies: which tenant credential to present. */
    public static final String CREDENTIAL_KEY = "credential";

    private static final Set<String> ALLOWED_KEYS = Set.of(CREDENTIAL_KEY);

    /**
     * Key names that suggest someone pasted a secret into the configuration. Rejecting these is a
     * guardrail, not a security boundary — but it catches the common mistake before the value is
     * readable by every editor in the tenant.
     */
    private static final List<String> SECRET_LIKE = List.of(
            "secret", "password", "passwd", "token", "apikey", "api_key", "authorization",
            "privatekey", "private_key", "clientsecret", "client_secret", "bearer", "credentials");

    public static final int MAX_NAME_LENGTH = 128;

    private ToolAuthRules() {
    }

    public static boolean isValidCredentialName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            boolean allowed = Character.isLetterOrDigit(character)
                    || character == '-' || character == '_' || character == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** True when this key is one the auth block accepts. */
    public static boolean isAllowedAuthKey(String key) {
        return key != null && ALLOWED_KEYS.contains(key.strip());
    }

    /** True when a configuration key name looks like somebody inlined a secret. */
    public static boolean looksLikeInlinedSecret(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.strip().toLowerCase(Locale.ROOT).replace("-", "");
        return SECRET_LIKE.stream().anyMatch(normalized::contains);
    }

    public static String normalizeName(String name) {
        return name == null ? "" : name.strip();
    }
}
