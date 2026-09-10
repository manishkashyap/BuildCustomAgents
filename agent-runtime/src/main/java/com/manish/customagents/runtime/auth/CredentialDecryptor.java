package com.manish.customagents.runtime.auth;

import com.manish.customagents.contracts.CredentialCipher;
import com.manish.customagents.runtime.errors.AgentExecutionException;

/**
 * Decrypts credential secrets, or explains clearly why it cannot.
 *
 * <p>A deployment with no authenticated tools should still start, so a missing key is not a startup
 * failure. It becomes an error on the first authenticated tool call instead, naming the setting that
 * is missing — which is far easier to act on than an AES tag mismatch.
 */
public final class CredentialDecryptor {

    private final CredentialCipher cipher;

    private CredentialDecryptor(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    public static CredentialDecryptor configured(CredentialCipher cipher) {
        return new CredentialDecryptor(cipher);
    }

    public static CredentialDecryptor unconfigured() {
        return new CredentialDecryptor(null);
    }

    public boolean isConfigured() {
        return cipher != null;
    }

    public String decrypt(String serialised) {
        if (cipher == null) {
            throw new AgentExecutionException("This tool requires a credential, but agent-runtime has no credential encryption "
                            + "key configured. Set AGENT_CREDENTIAL_KEY to the same value "
                            + "agent-management used to encrypt the secret.");
        }
        try {
            return cipher.decrypt(serialised);
        } catch (RuntimeException exception) {
            // The cause carries no secret material, but the message would name the algorithm; keep it
            // actionable and say nothing about the ciphertext itself.
            throw new AgentExecutionException("Stored credential could not be decrypted; agent-runtime's credential encryption "
                            + "key does not match the one agent-management wrote it with", exception);
        }
    }
}
