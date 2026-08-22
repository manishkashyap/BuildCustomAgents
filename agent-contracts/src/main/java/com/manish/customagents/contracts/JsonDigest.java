package com.manish.customagents.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one definition of the digest used for draft revisions and idempotency hashes.
 *
 * <p>Draft-revision pinning compares a value produced by the runtime against one a caller echoes
 * back, so every producer must agree byte for byte. A single implementation makes that structural
 * rather than coincidental.
 */
public final class JsonDigest {

    private JsonDigest() {
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
