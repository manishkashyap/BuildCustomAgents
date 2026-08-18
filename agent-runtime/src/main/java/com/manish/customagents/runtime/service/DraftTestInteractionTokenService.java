package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.config.DraftAgentTestProperties;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.errors.InvalidTestInteractionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class DraftTestInteractionTokenService {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final byte[] secret;
    private final long ttlSeconds;

    public DraftTestInteractionTokenService(
            ObjectMapper objectMapper,
            Clock clock,
            DraftAgentTestProperties properties) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secret = properties.getInteractionTokenSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = properties.getInteractionTokenTtl().toSeconds();
        if (ttlSeconds < 60) {
            throw new IllegalArgumentException("Draft test interaction token TTL must be at least 60 seconds");
        }
    }

    public String issue(
            String licenseCode,
            String rootAgentId,
            String draftRevision,
            String requestFingerprint,
            InteractionClaims interaction) {
        Instant issuedAt = clock.instant();
        TokenPayload payload = new TokenPayload(
                licenseCode, rootAgentId, draftRevision, requestFingerprint,
                interaction.interactionId(), interaction.interactionKey(), interaction.type(),
                interaction.category(), interaction.question(), interaction.reason(),
                interaction.responseType(), interaction.request(), interaction.toolBinding(),
                issuedAt.getEpochSecond(), issuedAt.plusSeconds(ttlSeconds).getEpochSecond());
        try {
            String encodedPayload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
            return encodedPayload + "." + ENCODER.encodeToString(sign(encodedPayload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to create draft test interaction token", exception);
        }
    }

    public TokenPayload verify(
            String token,
            String licenseCode,
            String rootAgentId,
            String draftRevision,
            String requestFingerprint) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw invalid();
            }
            if (!MessageDigest.isEqual(sign(parts[0]), DECODER.decode(parts[1]))) {
                throw invalid();
            }
            TokenPayload payload = objectMapper.readValue(DECODER.decode(parts[0]), TokenPayload.class);
            if (!licenseCode.equals(payload.licenseCode())
                    || !rootAgentId.equals(payload.rootAgentId())
                    || !draftRevision.equals(payload.draftRevision())
                    || !requestFingerprint.equals(payload.requestFingerprint())) {
                throw new InvalidTestInteractionException(
                        "Draft test interaction token does not belong to this test request");
            }
            if (payload.expiresAtEpochSecond() < clock.instant().getEpochSecond()) {
                throw new InvalidTestInteractionException("Draft test interaction token has expired");
            }
            return payload;
        } catch (InvalidTestInteractionException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new InvalidTestInteractionException("Draft test interaction token is invalid", exception);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private InvalidTestInteractionException invalid() {
        return new InvalidTestInteractionException("Draft test interaction token is invalid");
    }

    public record InteractionClaims(
            String interactionId,
            String interactionKey,
            HumanInteractionType type,
            String category,
            String question,
            String reason,
            HumanResponseType responseType,
            JsonNode request,
            String toolBinding) {
    }

    public record TokenPayload(
            String licenseCode,
            String rootAgentId,
            String draftRevision,
            String requestFingerprint,
            String interactionId,
            String interactionKey,
            HumanInteractionType type,
            String category,
            String question,
            String reason,
            HumanResponseType responseType,
            JsonNode request,
            String toolBinding,
            long issuedAtEpochSecond,
            long expiresAtEpochSecond) {
    }
}
