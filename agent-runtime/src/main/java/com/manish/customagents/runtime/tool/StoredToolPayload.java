package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.ToolType;

public record StoredToolPayload(
        String name,
        String description,
        ToolType type,
        JsonNode inputSchema,
        JsonNode configuration,
        JsonNode executionPolicy) {
}
