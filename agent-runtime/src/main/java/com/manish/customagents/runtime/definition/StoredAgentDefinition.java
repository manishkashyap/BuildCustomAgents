package com.manish.customagents.runtime.definition;

public record StoredAgentDefinition(
        String id,
        String licenseCode,
        String status,
        int version,
        PublishedAgentDefinition definition) {
}
