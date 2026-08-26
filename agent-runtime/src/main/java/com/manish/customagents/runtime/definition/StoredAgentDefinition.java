package com.manish.customagents.runtime.definition;

/**
 * One agent version as stored by management.
 *
 * @param status        the version's own status (DRAFT, PUBLISHED, SUPERSEDED, RETIRED)
 * @param lineageStatus the identity's status (ACTIVE, RETIRING, RETIRED)
 */
public record StoredAgentDefinition(
        String id,
        String licenseCode,
        String status,
        String lineageStatus,
        int version,
        PublishedAgentDefinition definition) {
}
