package com.manish.customagents.runtime.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime's half of the credential configuration. The encryption key must match Management's:
 * Management writes the ciphertext, Runtime reads it, and neither stores the key.
 */
@ConfigurationProperties("agent-platform.credentials")
public class CredentialProperties {

    private String encryptionKey;
    private Duration cacheTtl = Duration.ofSeconds(60);
    private Duration tokenRefreshSkew = Duration.ofSeconds(60);

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    /** How long a credential record is cached, and therefore the delay on a rotation or a disable. */
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() ? Duration.ofSeconds(60) : cacheTtl;
    }

    /** How early an exchanged token is treated as expired, so a call never races the expiry. */
    public Duration getTokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
        this.tokenRefreshSkew = tokenRefreshSkew == null || tokenRefreshSkew.isNegative()
                ? Duration.ofSeconds(60)
                : tokenRefreshSkew;
    }
}
