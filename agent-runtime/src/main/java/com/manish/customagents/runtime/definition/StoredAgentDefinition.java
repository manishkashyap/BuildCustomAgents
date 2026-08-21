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

    /** Assumes an active identity; convenient where lineage state is not under test. */
    public StoredAgentDefinition(String id, String licenseCode, String status, int version,
            PublishedAgentDefinition definition) {
        this(id, licenseCode, status, "ACTIVE", version, definition);
    }
}
