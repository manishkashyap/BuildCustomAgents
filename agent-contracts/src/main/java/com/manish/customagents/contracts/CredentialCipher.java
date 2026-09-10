package com.manish.customagents.contracts;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption for credential secrets at rest.
 *
 * <p>Lives in contracts because Management writes these values and Runtime reads them: one
 * implementation means the two cannot disagree about the format. The key never travels with the
 * ciphertext — both services take it from their environment.
 *
 * <p>Serialised form is {@code v1.<base64url iv>.<base64url ciphertext+tag>}. The version prefix and
 * the separate {@code key_id} column are what make rotation possible without re-encrypting history:
 * a record decrypts with the key it was written under.
 */
public final class CredentialCipher {

    public static final String CURRENT_VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param key exactly 32 bytes of key material. Passing a short key is a configuration error and
     *            fails here rather than producing weakly encrypted records.
     */
    public CredentialCipher(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "credential encryption key must be exactly " + KEY_BYTES + " bytes (256 bits)");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** Decodes a base64 or base64url key of exactly 32 bytes. */
    public static CredentialCipher fromEncodedKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "credential encryption key is not configured; set AGENT_CREDENTIAL_KEY to a "
                            + "base64-encoded 256-bit key");
        }
        String trimmed = encoded.strip();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException notStandard) {
            decoded = Base64.getUrlDecoder().decode(trimmed);
        }
        return new CredentialCipher(decoded);
    }

    public String encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return CURRENT_VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(sealed);
        } catch (Exception exception) {
            // Deliberately does not include the plaintext or the key in the message.
            throw new IllegalStateException("Unable to encrypt the credential secret", exception);
        }
    }

    public String decrypt(String serialised) {
        if (serialised == null || serialised.isBlank()) {
            throw new IllegalStateException("Stored credential secret is empty");
        }
        String[] parts = serialised.split("\\.", 3);
        if (parts.length != 3 || !CURRENT_VERSION.equals(parts[0])) {
            throw new IllegalStateException("Stored credential secret is not in a recognised format");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] sealed = decoder.decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            // A tag mismatch means the wrong key or a tampered record; either way say no more.
            throw new IllegalStateException(
                    "Unable to decrypt the credential secret; the encryption key may have changed", exception);
        }
    }
}
