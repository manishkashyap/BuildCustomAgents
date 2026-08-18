package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishedAgentDefinition(
        String name,
        String description,
        String role,
        String instructions,
        List<String> rules,
        String outputFormat,
        JsonNode outputSchema,
        JsonNode context,
        List<AgentExampleDefinition> examples,
        Set<String> allowedTools,
        JsonNode humanInteractionPolicy) {

    public PublishedAgentDefinition {
        rules = rules == null ? List.of() : List.copyOf(rules);
        examples = examples == null ? List.of() : List.copyOf(examples);
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    }
}
