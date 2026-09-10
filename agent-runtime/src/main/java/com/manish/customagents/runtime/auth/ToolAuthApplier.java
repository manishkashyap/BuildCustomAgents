package com.manish.customagents.runtime.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.ToolAuthRules;
import com.manish.customagents.runtime.definition.ManagementCredentialRepository;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Turns a tool's {@code configuration.auth} reference into the headers, or the query parameter, that
 * the outgoing request needs.
 *
 * <p>Applied after the URL has been expanded and vetted by the egress guard, so a credential is only
 * ever attached to a request that was already going to be allowed. Nothing here is written to the
 * invocation record or the run trace: only tool arguments and results are persisted, and a
 * credential is neither.
 */
@Component
public class ToolAuthApplier {

    private final ManagementCredentialRepository credentials;
    private final AccessTokenExchange tokenExchange;

    public ToolAuthApplier(
            ManagementCredentialRepository credentials, AccessTokenExchange tokenExchange) {
        this.credentials = credentials;
        this.tokenExchange = tokenExchange;
    }

    /** The credential a configuration references, or null when the tool needs no authentication. */
    public String credentialNameOf(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            return null;
        }
        JsonNode auth = configuration.path("auth");
        if (!auth.isObject()) {
            return null;
        }
        String name = auth.path(ToolAuthRules.CREDENTIAL_KEY).asText("").strip();
        return name.isEmpty() ? null : name;
    }

    public AppliedAuth apply(URI uri, JsonNode configuration, String licenseCode) {
        String credentialName = credentialNameOf(configuration);
        if (credentialName == null) {
            return AppliedAuth.none(uri);
        }
        ResolvedCredential credential = credentials.require(licenseCode, credentialName);
        return switch (credential.type()) {
            case API_KEY_HEADER -> {
                String headerName = requireSetting(credential, "headerName");
                String prefix = credential.rawSetting("valuePrefix");
                String value = prefix.isEmpty() ? credential.secret() : prefix + credential.secret();
                yield AppliedAuth.header(uri, headerName, value);
            }
            case API_KEY_QUERY -> {
                String parameter = requireSetting(credential, "queryParameter");
                // Appended after the guard has vetted the host, and the host cannot be changed by
                // adding a query parameter, so the check still holds.
                URI withKey = UriComponentsBuilder.fromUri(uri)
                        .queryParam(parameter, credential.secret())
                        .build(true)
                        .toUri();
                yield AppliedAuth.none(withKey);
            }
            case BEARER_STATIC -> AppliedAuth.header(
                    uri, HttpHeaders.AUTHORIZATION, "Bearer " + credential.secret());
            case BASIC -> {
                String username = requireSetting(credential, "username");
                String encoded = Base64.getEncoder().encodeToString(
                        (username + ":" + credential.secret()).getBytes(StandardCharsets.UTF_8));
                yield AppliedAuth.header(uri, HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            }
            case OAUTH2_CLIENT_CREDENTIALS, GOOGLE_SERVICE_ACCOUNT -> AppliedAuth.header(
                    uri, HttpHeaders.AUTHORIZATION,
                    "Bearer " + tokenExchange.bearerToken(licenseCode, credential));
        };
    }

    /**
     * Called after a 401 so an expired cached token is exchanged again rather than failing the run.
     * Only meaningful for the exchange types; a rejected static key will not improve on a retry.
     */
    public boolean retryableAfterUnauthorized(JsonNode configuration, String licenseCode) {
        String credentialName = credentialNameOf(configuration);
        if (credentialName == null) {
            return false;
        }
        ResolvedCredential credential = credentials.require(licenseCode, credentialName);
        if (!credential.type().requiresTokenExchange()) {
            return false;
        }
        tokenExchange.invalidate(licenseCode, credential);
        return true;
    }

    private String requireSetting(ResolvedCredential credential, String key) {
        String value = credential.setting(key);
        if (value.isEmpty()) {
            throw new AgentExecutionException("Credential " + credential.name() + " is missing settings." + key);
        }
        return value;
    }
}
