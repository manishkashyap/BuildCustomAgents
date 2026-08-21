package com.manish.customagents.agent.enums;

/**
 * Persisted status of one authored revision.
 *
 * <p>A version becomes {@code SUPERSEDED} rather than {@code RETIRED} when a newer version
 * takes over, so that it remains a valid rollback target.
 */
public enum AgentVersionStatus {
    DRAFT,
    PUBLISHED,
    SUPERSEDED,
    RETIRED
}
