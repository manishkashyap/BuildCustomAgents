package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.runtime.model.ToolDefinition;
import org.junit.jupiter.api.Test;

class PublishedToolDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendsTheResponseShapeToTheDescriptionTheModelSees() {
        ObjectNode outputSchema = objectMapper.createObjectNode();
        outputSchema.put("type", "object");
        outputSchema.putObject("properties").putObject("bid").put("type", "number");

        ToolDefinition llmDefinition = definition(outputSchema).toLlmDefinition();

        assertThat(llmDefinition.description())
                .startsWith("Fetches a quote")
                .contains("RESPONSE SCHEMA")
                .contains("\"bid\"");
    }

    @Test
    void leavesTheDescriptionAloneWhenNoResponseShapeIsDeclared() {
        assertThat(definition(null).toLlmDefinition().description()).isEqualTo("Fetches a quote");
        assertThat(definition(objectMapper.createObjectNode()).modelFacingDescription())
                .isEqualTo("Fetches a quote");
    }

    @Test
    void treatsAMissingOutputSchemaAsAbsentRatherThanInvalid() {
        // Definitions authored before output schemas existed deserialize with a null here.
        assertThat(definition(null).outputSchema()).isNull();
        assertThat(definition(objectMapper.nullNode()).outputSchema()).isNull();
    }

    @Test
    void rejectsAnOutputSchemaThatIsNotAnObject() {
        assertThatThrownBy(() -> definition(objectMapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool output schema");
    }

    @Test
    void keepsTheLegacyConstructorForDefinitionsWithoutAResponseShape() {
        PublishedToolDefinition legacy = new PublishedToolDefinition(
                "tool-1", "market_get_quote", "Fetches a quote", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode(), objectMapper.createObjectNode());

        assertThat(legacy.outputSchema()).isNull();
        assertThat(legacy.modelFacingDescription()).isEqualTo("Fetches a quote");
    }

    @Test
    void readsTheOutputSchemaOutOfADefinitionWrittenByManagement() throws Exception {
        // Field names must match what CreateToolRequest serialises, or the schema silently
        // disappears between the two services.
        String stored = """
                {"name":"market_get_quote","description":"Fetches a quote","type":"HTTP",
                 "inputSchema":{"type":"object"},
                 "outputSchema":{"type":"object","properties":{"bid":{"type":"number"}}},
                 "configuration":{"method":"GET","url":"https://example.com/quote"},
                 "executionPolicy":{"operation":"READ"}}
                """;

        StoredToolPayload payload = objectMapper.readValue(stored, StoredToolPayload.class);

        assertThat(payload.outputSchema().path("properties").has("bid")).isTrue();
    }

    @Test
    void readsADefinitionWrittenBeforeOutputSchemasExisted() throws Exception {
        String legacy = """
                {"name":"market_get_quote","description":"Fetches a quote","type":"HTTP",
                 "inputSchema":{"type":"object"},
                 "configuration":{"method":"GET","url":"https://example.com/quote"},
                 "executionPolicy":{}}
                """;

        StoredToolPayload payload = objectMapper.readValue(legacy, StoredToolPayload.class);

        assertThat(payload.outputSchema()).isNull();
    }

    private PublishedToolDefinition definition(com.fasterxml.jackson.databind.JsonNode outputSchema) {
        return new PublishedToolDefinition(
                "tool-1", "market_get_quote", "Fetches a quote", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                outputSchema,
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode());
    }
}
