package com.manish.customagents.credential.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.error.InvalidCredentialSettingsException;
import org.springframework.stereotype.Component;

/**
 * Checks that a credential's non-secret settings carry what its type needs.
 *
 * <p>Validated here rather than at first use, because the alternative is a credential that looks
 * fine in the console and fails inside an unattended run hours later, where the only symptom is a
 * failed tool call.
 */
@Component
public class CredentialSettingsValidator {

    public static final String HEADER_NAME = "headerName";
    public static final String QUERY_PARAMETER = "queryParameter";
    public static final String USERNAME = "username";
    public static final String TOKEN_URL = "tokenUrl";
    public static final String CLIENT_ID = "clientId";
    public static final String SCOPES = "scopes";
    public static final String VALUE_PREFIX = "valuePrefix";

    public void validate(CredentialType type, JsonNode settings) {
        JsonNode node = settings == null ? null : settings;
        switch (type) {
            case API_KEY_HEADER -> requireText(node, HEADER_NAME, type,
                    "the header the key is sent in, for example X-API-Key");
            case API_KEY_QUERY -> requireText(node, QUERY_PARAMETER, type,
                    "the query parameter the key is sent in, for example key");
            case BASIC -> requireText(node, USERNAME, type, "the username to pair with the secret");
            case OAUTH2_CLIENT_CREDENTIALS -> {
                String tokenUrl = requireText(node, TOKEN_URL, type, "the token endpoint URL");
                requireHttps(tokenUrl, TOKEN_URL);
                requireText(node, CLIENT_ID, type, "the OAuth2 client id");
            }
            case GOOGLE_SERVICE_ACCOUNT -> requireText(node, SCOPES, type,
                    "space or comma separated OAuth scopes, for example "
                            + "https://www.googleapis.com/auth/spreadsheets");
            case BEARER_STATIC -> {
                // Nothing required: the secret is sent verbatim as the bearer token.
            }
        }
    }

    private String requireText(JsonNode settings, String field, CredentialType type, String what) {
        String value = settings == null ? "" : settings.path(field).asText("").strip();
        if (value.isEmpty()) {
            throw new InvalidCredentialSettingsException(
                    type + " requires settings." + field + ": " + what);
        }
        return value;
    }

    private void requireHttps(String url, String field) {
        // Plain http would put the client secret on the wire in clear text.
        if (!url.startsWith("https://")) {
            throw new InvalidCredentialSettingsException(
                    "settings." + field + " must be an https URL");
        }
    }
}
