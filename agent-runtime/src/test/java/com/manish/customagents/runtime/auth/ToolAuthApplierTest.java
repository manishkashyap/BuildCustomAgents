package com.manish.customagents.runtime.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.runtime.definition.ManagementCredentialRepository;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolAuthApplierTest {

    private static final URI TARGET = URI.create("https://api.example.com/v1/values?range=A1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ManagementCredentialRepository credentials;

    @Mock
    private AccessTokenExchange tokenExchange;

    private ToolAuthApplier applier() {
        return new ToolAuthApplier(credentials, tokenExchange);
    }

    private ObjectNode configWithCredential(String name) {
        ObjectNode configuration = objectMapper.createObjectNode()
                .put("method", "GET").put("url", TARGET.toString());
        configuration.putObject("auth").put("credential", name);
        return configuration;
    }

    private void stub(CredentialType type, String secret, String... settingPairs) {
        ObjectNode settings = objectMapper.createObjectNode();
        for (int index = 0; index + 1 < settingPairs.length; index += 2) {
            settings.put(settingPairs[index], settingPairs[index + 1]);
        }
        when(credentials.require("tenant-1", "cred"))
                .thenReturn(new ResolvedCredential("cred-1", "cred", type, secret, settings));
    }

    /** A tool with no auth block must not consult the credential store at all. */
    @Test
    void leavesAnUnauthenticatedToolUntouched() {
        ObjectNode configuration = objectMapper.createObjectNode()
                .put("method", "GET").put("url", TARGET.toString());

        AppliedAuth applied = applier().apply(TARGET, configuration, "tenant-1");

        assertThat(applied.headers()).isEmpty();
        assertThat(applied.uri()).isEqualTo(TARGET);
    }

    @Test
    void sendsAnApiKeyInTheConfiguredHeader() {
        stub(CredentialType.API_KEY_HEADER, "key-value", "headerName", "X-API-Key");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        assertThat(applied.headers()).containsEntry("X-API-Key", "key-value");
    }

    @Test
    void appliesAConfiguredValuePrefix() {
        stub(CredentialType.API_KEY_HEADER, "abc",
                "headerName", "Authorization", "valuePrefix", "Token ");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        assertThat(applied.headers()).containsEntry("Authorization", "Token abc");
    }

    /** A query credential changes the URI rather than adding a header, and must keep the existing query. */
    @Test
    void appendsAnApiKeyToTheQueryWithoutLosingExistingParameters() {
        stub(CredentialType.API_KEY_QUERY, "qkey", "queryParameter", "key");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        assertThat(applied.headers()).isEmpty();
        assertThat(applied.uri().toString()).contains("range=A1").contains("key=qkey");
        assertThat(applied.uri().getHost()).isEqualTo("api.example.com");
    }

    @Test
    void sendsAStaticBearerToken() {
        stub(CredentialType.BEARER_STATIC, "static-token");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        assertThat(applied.headers()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer static-token");
    }

    @Test
    void encodesBasicCredentials() {
        stub(CredentialType.BASIC, "s3cret", "username", "svc-user");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        String expected = Base64.getEncoder()
                .encodeToString("svc-user:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(applied.headers()).containsEntry(HttpHeaders.AUTHORIZATION, "Basic " + expected);
    }

    @Test
    void exchangesAServiceAccountForABearerToken() {
        stub(CredentialType.GOOGLE_SERVICE_ACCOUNT, "{}", "scopes", "https://x/auth/spreadsheets");
        when(tokenExchange.bearerToken(org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.any())).thenReturn("exchanged-token");

        AppliedAuth applied = applier().apply(TARGET, configWithCredential("cred"), "tenant-1");

        assertThat(applied.headers()).containsEntry(HttpHeaders.AUTHORIZATION, "Bearer exchanged-token");
    }

    @Test
    void failsClearlyWhenARequiredSettingIsMissing() {
        stub(CredentialType.API_KEY_HEADER, "key-value");

        assertThatThrownBy(() -> applier().apply(TARGET, configWithCredential("cred"), "tenant-1"))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("settings.headerName");
    }

    /** Retrying only helps an exchanged token; a rejected static key will not improve. */
    @Test
    void retriesOnlyForCredentialTypesThatExchangeAToken() {
        stub(CredentialType.BEARER_STATIC, "static-token");
        assertThat(applier().retryableAfterUnauthorized(configWithCredential("cred"), "tenant-1"))
                .isFalse();

        stub(CredentialType.OAUTH2_CLIENT_CREDENTIALS, "client-secret",
                "tokenUrl", "https://auth.example.com/token", "clientId", "abc");
        assertThat(applier().retryableAfterUnauthorized(configWithCredential("cred"), "tenant-1"))
                .isTrue();
    }

    /** The secret must not be recoverable from a log line or an exception message. */
    @Test
    void redactsTheSecretInToString() {
        ResolvedCredential credential = new ResolvedCredential(
                "cred-1", "cred", CredentialType.BEARER_STATIC, "super-secret",
                objectMapper.createObjectNode());

        assertThat(credential.toString()).doesNotContain("super-secret").contains("redacted");
        assertThat(AppliedAuth.header(TARGET, HttpHeaders.AUTHORIZATION, "Bearer super-secret")
                .toString()).doesNotContain("super-secret");
    }
}
