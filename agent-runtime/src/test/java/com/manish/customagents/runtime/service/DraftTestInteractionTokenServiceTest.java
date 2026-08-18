package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.config.DraftAgentTestProperties;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.errors.InvalidTestInteractionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DraftTestInteractionTokenServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DraftAgentTestProperties properties = new DraftAgentTestProperties();
    private final Clock issuedAt = Clock.fixed(
            Instant.parse("2026-08-14T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void verifiesAStatelessTokenOnAnotherRuntimeInstanceWithTheSameSecret() {
        DraftTestInteractionTokenService issuer = service(issuedAt);
        String token = issuer.issue(
                "tenant-1", "agent-1", "a".repeat(64), "request-hash",
                claims());

        DraftTestInteractionTokenService.TokenPayload payload = service(issuedAt).verify(
                token, "tenant-1", "agent-1", "a".repeat(64), "request-hash");

        assertThat(payload.interactionId()).isEqualTo("interaction-1");
        assertThat(payload.question()).isEqualTo("Which city?");
    }

    @Test
    void rejectsTamperingAndExpiry() {
        String token = service(issuedAt).issue(
                "tenant-1", "agent-1", "a".repeat(64), "request-hash",
                claims());

        assertThatThrownBy(() -> service(issuedAt).verify(
                token + "x", "tenant-1", "agent-1", "a".repeat(64), "request-hash"))
                .isInstanceOf(InvalidTestInteractionException.class);

        Clock expired = Clock.fixed(Instant.parse("2026-08-14T08:31:00Z"), ZoneOffset.UTC);
        assertThatThrownBy(() -> service(expired).verify(
                token, "tenant-1", "agent-1", "a".repeat(64), "request-hash"))
                .isInstanceOf(InvalidTestInteractionException.class)
                .hasMessageContaining("expired");
    }

    private DraftTestInteractionTokenService service(Clock clock) {
        properties.setInteractionTokenTtl(Duration.ofMinutes(30));
        return new DraftTestInteractionTokenService(objectMapper, clock, properties);
    }

    private DraftTestInteractionTokenService.InteractionClaims claims() {
        return new DraftTestInteractionTokenService.InteractionClaims(
                "interaction-1", "interaction-key", HumanInteractionType.CLARIFICATION,
                "LOCATION", "Which city?", "A city is required", HumanResponseType.FREE_TEXT,
                objectMapper.createObjectNode().put("question", "Which city?"), null);
    }
}
