package com.manish.customagents.credential.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.credential.enums.CredentialStatus;
import java.time.Instant;

/**
 * Deliberately has no secret field, and no ciphertext field either. This record is the whole reason
 * a credential can be managed by anyone who authors tools without the secret becoming readable:
 * there is no representation in which it comes back out.
 */
public record CredentialResponse(
        String id,
        String licenseCode,
        String name,
        CredentialType type,
        String description,
        JsonNode settings,
        CredentialStatus status,
        String keyId,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy) {
}
