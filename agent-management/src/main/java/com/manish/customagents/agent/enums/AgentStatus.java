package com.manish.customagents.agent.enums;

/**
 * Agent status as exposed by the management API. It is derived from the lineage status
 * and the status of the version being reported, and is not persisted directly.
 */
public enum AgentStatus {
    DRAFT,
    PUBLISHED,
    SUPERSEDED,
    RETIRING,
    RETIRED
}
