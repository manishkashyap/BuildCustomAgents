package com.manish.customagents.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsASecret() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String secret = "{\"type\":\"service_account\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\"}";

        assertThat(cipher.decrypt(cipher.encrypt(secret))).isEqualTo(secret);
    }

    /** GCM uses a fresh IV per record, so the same plaintext must not produce the same ciphertext. */
    @Test
    void producesDifferentCiphertextForTheSamePlaintext() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void writesTheVersionedFormat() {
        String sealed = new CredentialCipher(KEY).encrypt("value");

        assertThat(sealed).startsWith(CredentialCipher.CURRENT_VERSION + ".");
        assertThat(sealed.split("\\.")).hasSize(3);
    }

    @Test
    void refusesToDecryptWithADifferentKey() {
        String sealed = new CredentialCipher(KEY).encrypt("value");

        assertThatThrownBy(() -> new CredentialCipher(OTHER_KEY).decrypt(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encryption key may have changed");
    }

    /** The authentication tag is the point: a tampered record must not decrypt to anything. */
    @Test
    void refusesATamperedRecord() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String sealed = cipher.encrypt("value");
        String[] parts = sealed.split("\\.", 3);
        byte[] payload = Base64.getUrlDecoder().decode(parts[2]);
        payload[0] ^= 0x01;
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAKeyOfTheWrongLength() {
        assertThatThrownBy(() -> new CredentialCipher("too-short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void reportsAMissingKeyAsConfiguration() {
        assertThatThrownBy(() -> CredentialCipher.fromEncodedKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_CREDENTIAL_KEY");
    }

    @Test
    void acceptsAStandardOrUrlSafeEncodedKey() {
        String standard = Base64.getEncoder().encodeToString(KEY);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(KEY);

        assertThat(CredentialCipher.fromEncodedKey(standard).decrypt(
                CredentialCipher.fromEncodedKey(urlSafe).encrypt("shared"))).isEqualTo("shared");
    }

    @Test
    void refusesAnUnrecognisedStoredFormat() {
        assertThatThrownBy(() -> new CredentialCipher(KEY).decrypt("v9.aa.bb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("recognised format");
    }
}
