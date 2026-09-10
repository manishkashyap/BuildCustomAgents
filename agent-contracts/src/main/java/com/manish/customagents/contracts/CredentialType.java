package com.manish.customagents.contracts;

/**
 * The authentication schemes an HTTP tool can use.
 *
 * <p>All of these are machine-to-machine: the platform holds the credential and presents it. None
 * requires an interactive consent step, which is what keeps them usable from an unattended run.
 * User-delegated OAuth (authorization code plus refresh token) is deliberately absent — it needs a
 * browser redirect and a per-user token store, and "on behalf of which user?" has no answer inside
 * a scheduled run.
 */
public enum CredentialType {

    /** Secret sent as the value of a configured request header. */
    API_KEY_HEADER,

    /** Secret sent as the value of a configured query parameter. */
    API_KEY_QUERY,

    /** Secret sent verbatim as {@code Authorization: Bearer <secret>}. */
    BEARER_STATIC,

    /** Configured username with the secret as the password, sent as HTTP Basic. */
    BASIC,

    /** Secret is the client secret; exchanged at a token endpoint for a short-lived access token. */
    OAUTH2_CLIENT_CREDENTIALS,

    /**
     * Secret is a Google service-account JSON key. A signed JWT assertion is exchanged for an access
     * token. This is what reaches Sheets, Drive and the rest of Google's APIs without a user present.
     */
    GOOGLE_SERVICE_ACCOUNT;

    /** True when using this type requires an outbound token exchange before the tool call. */
    public boolean requiresTokenExchange() {
        return this == OAUTH2_CLIENT_CREDENTIALS || this == GOOGLE_SERVICE_ACCOUNT;
    }
}
