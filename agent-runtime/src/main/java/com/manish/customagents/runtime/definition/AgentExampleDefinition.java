package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.databind.JsonNode;

public record AgentExampleDefinition(String description, JsonNode input, JsonNode expectedOutput) {
}
