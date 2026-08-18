package com.manish.customagents.runtime.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.service.AgentPromptBuilder;
import org.junit.jupiter.api.Test;

class PublishedAgentDefinitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ignoresLegacyInputSchemaWhenLoadingAndBuildingThePrompt() throws Exception {
        PublishedAgentDefinition definition = objectMapper.readValue("""
                {
                  "name": "Legacy agent",
                  "role": "Research assistant",
                  "instructions": "Research the requested topic.",
                  "rules": [],
                  "inputSchema": {
                    "type": "object",
                    "legacyMarker": "must-not-reach-the-prompt"
                  },
                  "examples": [],
                  "allowedTools": []
                }
                """, PublishedAgentDefinition.class);

        var messages = new AgentPromptBuilder(objectMapper).build(
                definition,
                "Research platform",
                objectMapper.createObjectNode().put("topic", "customer engagement"));

        assertThat(messages.getFirst().content())
                .doesNotContain("inputSchema")
                .doesNotContain("legacyMarker")
                .doesNotContain("must-not-reach-the-prompt");
    }
}
