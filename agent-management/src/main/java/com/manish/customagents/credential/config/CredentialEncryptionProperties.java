package com.manish.customagents.credential.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the credential encryption key comes from.
 *
 * <p>The key is read from the environment and never stored beside the ciphertext it protects.
 * {@code keyId} is recorded on each encrypted row so a rotated key can still read older records:
 * change the key and the id together, and history stays readable under the old id.
 */
@ConfigurationProperties("agent-platform.credentials")
public class CredentialEncryptionProperties {

    private String encryptionKey;
    private String keyId = "primary";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId == null || keyId.isBlank() ? "primary" : keyId.strip();
    }
}
