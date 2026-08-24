package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.model.ToolDefinition;

import java.util.Objects;
import com.manish.customagents.contracts.ToolType;

public record PublishedToolDefinition(
        String id,
        String name,
        String description,
        ToolType type,
        int version,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode configuration,
        JsonNode executionPolicy) {

    public PublishedToolDefinition {
        id = requireText(id, "tool id");
        name = requireText(name, "tool name");
        description = requireText(description, "tool description");
        type = Objects.requireNonNull(type, "tool type must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("tool version must be greater than zero");
        }
        inputSchema = requireObject(inputSchema, "tool input schema");
        outputSchema = outputSchema == null || outputSchema.isNull()
                ? null
                : requireObject(outputSchema, "tool output schema");
        configuration = requireObject(configuration, "tool configuration");
        executionPolicy = executionPolicy == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : requireObject(executionPolicy, "tool execution policy");
    }

    /** A definition with no declared response shape. */
    public PublishedToolDefinition(
            String id, String name, String description, ToolType type, int version,
            JsonNode inputSchema, JsonNode configuration, JsonNode executionPolicy) {
        this(id, name, description, type, version, inputSchema, null, configuration, executionPolicy);
    }

    public ToolDefinition toLlmDefinition() {
        return new ToolDefinition(name, modelFacingDescription(), inputSchema);
    }

    /**
     * The description as the provider sees it, with the response shape appended.
     *
     * <p>Function-calling APIs carry a name, a description and a parameter schema — there is
     * no field for what a tool returns. Appending the schema to the description is the only
     * way to hand the model a machine-readable response contract, and it costs nothing at the
     * mapper layer: both providers already send the description as a plain string.
     */
    public String modelFacingDescription() {
        if (outputSchema == null || outputSchema.isEmpty()) {
            return description;
        }
        return description + "\n\nRESPONSE SCHEMA\n" + outputSchema;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    private static JsonNode requireObject(JsonNode value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (!value.isObject()) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        return value.deepCopy();
    }
}
