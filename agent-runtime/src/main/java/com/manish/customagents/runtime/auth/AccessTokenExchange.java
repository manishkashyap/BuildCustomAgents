package com.manish.customagents.runtime.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.runtime.config.CredentialProperties;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import com.manish.customagents.runtime.tool.HttpEgressGuard;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Exchanges a long-lived credential for a short-lived access token, and caches the result.
 *
 * <p>Covers the two machine-to-machine grants: OAuth2 client credentials, and the signed-JWT grant
 * Google uses for service accounts. Tokens are cached per credential until shortly before expiry, so
 * a five-minute schedule does not perform a token round trip on every tick.
 */
@Component
public class AccessTokenExchange {

    private static final String GOOGLE_JWT_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final Duration ASSERTION_LIFETIME = Duration.ofMinutes(30);
    private static final Duration FALLBACK_TOKEN_LIFETIME = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final HttpEgressGuard egressGuard;
    private final Duration refreshSkew;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public AccessTokenExchange(
            @Qualifier("httpToolRestClient") RestClient restClient,
            HttpEgressGuard egressGuard,
            CredentialProperties properties) {
        this.restClient = restClient;
        this.egressGuard = egressGuard;
        this.refreshSkew = properties.getTokenRefreshSkew();
    }

    /** A bearer token for this credential, exchanged if the cached one is missing or near expiry. */
    public String bearerToken(String licenseCode, ResolvedCredential credential) {
        String cacheKey = licenseCode + " " + credential.id();
        Instant now = Instant.now();
        CachedToken cached = cache.get(cacheKey);
        if (cached != null && cached.usableUntil().isAfter(now)) {
            return cached.token();
        }
        String token = switch (credential.type()) {
            case OAUTH2_CLIENT_CREDENTIALS -> exchangeClientCredentials(licenseCode, credential);
            case GOOGLE_SERVICE_ACCOUNT -> exchangeGoogleServiceAccount(licenseCode, credential);
            default -> throw new IllegalStateException(
                    credential.type() + " does not use a token exchange");
        };
        return token;
    }

    /** Forces the next call to exchange again; used after a 401 from the tool's own endpoint. */
    public void invalidate(String licenseCode, ResolvedCredential credential) {
        cache.remove(licenseCode + " " + credential.id());
    }

    private String exchangeClientCredentials(String licenseCode, ResolvedCredential credential) {
        String tokenUrl = credential.setting("tokenUrl");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", credential.setting("clientId"));
        form.add("client_secret", credential.secret());
        String scopes = credential.setting("scopes");
        if (!scopes.isEmpty()) {
            form.add("scope", scopes.replace(',', ' ').trim());
        }
        return post(licenseCode, credential, tokenUrl, form);
    }

    private String exchangeGoogleServiceAccount(String licenseCode, ResolvedCredential credential) {
        JsonNode key = parseServiceAccount(credential);
        String clientEmail = key.path("client_email").asText("");
        String tokenUrl = key.path("token_uri").asText("https://oauth2.googleapis.com/token");
        String privateKeyPem = key.path("private_key").asText("");
        if (clientEmail.isEmpty() || privateKeyPem.isEmpty()) {
            throw new AgentExecutionException("Credential " + credential.name()
                            + " is not a usable Google service-account key: client_email or private_key is missing");
        }
        String scopes = credential.setting("scopes").replace(',', ' ').trim();
        String assertion = signedAssertion(credential, clientEmail, scopes, tokenUrl, privateKeyPem);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GOOGLE_JWT_GRANT);
        form.add("assertion", assertion);
        return post(licenseCode, credential, tokenUrl, form);
    }

    private String post(String licenseCode, ResolvedCredential credential,
            String tokenUrl, MultiValueMap<String, String> form) {
        URI uri = URI.create(tokenUrl);
        // The token URL comes from tenant-supplied settings, so it is an outbound request the tenant
        // controls. Without this check it would be a straight path to link-local metadata, bypassing
        // every protection the tool URL itself gets.
        egressGuard.check(uri, licenseCode);
        JsonNode body;
        try {
            body = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            // The response body of a failed token exchange often echoes the client id; say nothing
            // beyond the host so a log line cannot become a credential leak.
            throw new AgentExecutionException("Token exchange for credential " + credential.name() + " failed at " + uri.getHost(),
                    exception);
        }
        if (body == null || body.path("access_token").asText("").isEmpty()) {
            throw new AgentExecutionException("Token exchange for credential " + credential.name()
                            + " returned no access_token");
        }
        String token = body.path("access_token").asText();
        long expiresIn = body.path("expires_in").asLong(0);
        Duration lifetime = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : FALLBACK_TOKEN_LIFETIME;
        Instant usableUntil = Instant.now().plus(lifetime).minus(refreshSkew);
        cache.put(licenseCode + " " + credential.id(), new CachedToken(token, usableUntil));
        return token;
    }

    private JsonNode parseServiceAccount(ResolvedCredential credential) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(credential.secret());
        } catch (Exception exception) {
            throw new AgentExecutionException("Credential " + credential.name()
                            + " is typed GOOGLE_SERVICE_ACCOUNT but its secret is not JSON", exception);
        }
    }

    /**
     * Builds and RS256-signs the JWT assertion Google exchanges for an access token. Done with the
     * JDK rather than a JOSE library: it is a fixed header, a fixed claim set and one signature, and
     * it keeps the credential path free of another dependency.
     */
    private String signedAssertion(ResolvedCredential credential, String issuer, String scopes,
            String audience, String privateKeyPem) {
        Instant now = Instant.now();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = base64Url("{"
                + "\"iss\":\"" + issuer + "\","
                + "\"scope\":\"" + scopes + "\","
                + "\"aud\":\"" + audience + "\","
                + "\"iat\":" + now.getEpochSecond() + ","
                + "\"exp\":" + now.plus(ASSERTION_LIFETIME).getEpochSecond()
                + "}");
        String signingInput = header + "." + claims;
        try {
            PrivateKey privateKey = readPkcs8(privateKeyPem);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
            return signingInput + "." + encoded;
        } catch (Exception exception) {
            throw new AgentExecutionException("Unable to sign the assertion for credential " + credential.name()
                            + "; its private key may be malformed", exception);
        }
    }

    private PrivateKey readPkcs8(String pem) throws Exception {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private record CachedToken(String token, Instant usableUntil) {
        @Override
        public String toString() {
            return "CachedToken[usableUntil=" + usableUntil + ", token=<redacted>]";
        }
    }
}
