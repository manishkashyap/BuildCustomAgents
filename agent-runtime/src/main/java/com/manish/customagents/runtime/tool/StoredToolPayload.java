package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.contracts.ToolType;

/**
 * The shape the runtime reads out of {@code custom_tools.definition_json}.
 *
 * <p>{@code outputSchema} is absent from definitions authored before it existed, which
 * deserializes to null and is handled as "no declared response shape" rather than an error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredToolPayload(
        String name,
        String description,
        ToolType type,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode configuration,
        JsonNode executionPolicy) {
}
