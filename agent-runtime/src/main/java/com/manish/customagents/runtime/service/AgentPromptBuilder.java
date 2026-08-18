package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.definition.AgentExampleDefinition;
import com.manish.customagents.runtime.definition.PublishedAgentDefinition;
import com.manish.customagents.runtime.errors.AgentExecutionException;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentPromptBuilder {

    private final ObjectMapper objectMapper;

    public AgentPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AgentMessage> build(PublishedAgentDefinition definition, String task, JsonNode input) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(systemPrompt(definition)));
        for (AgentExampleDefinition example : definition.examples()) {
            if (example.input() != null && example.expectedOutput() != null) {
                messages.add(AgentMessage.user(exampleUserMessage(example)));
                messages.add(AgentMessage.assistant(json(example.expectedOutput()), List.of()));
            }
        }
        messages.add(AgentMessage.user(userMessage(task, input)));
        return messages;
    }

    private String systemPrompt(PublishedAgentDefinition definition) {
        String rules = definition.rules().isEmpty()
                ? "No additional rules."
                : definition.rules().stream().map(rule -> "- " + rule).reduce((a, b) -> a + "\n" + b).orElse("");
        String context = definition.context() == null ? "No static context." : json(definition.context());
        return """
                AGENT
                %s

                ROLE AND RESPONSIBILITIES
                %s

                DESCRIPTION
                %s

                INSTRUCTIONS
                %s

                RULES
                %s

                EXPECTED OUTPUT
                %s

                STATIC CONTEXT
                %s

                Use only the tools supplied with this request. Decide which supplied tools and data are required.
                When tool results are returned, use them to continue until the requested output is complete.
                """.formatted(
                value(definition.name()), value(definition.role()), value(definition.description()),
                value(definition.instructions()), rules, value(definition.outputFormat()), context);
    }

    private String exampleUserMessage(AgentExampleDefinition example) {
        String description = example.description() == null ? "" : "\nDESCRIPTION\n" + example.description();
        return "EXAMPLE INPUT\n" + json(example.input()) + description;
    }

    private String userMessage(String task, JsonNode input) {
        String effectiveTask = task == null || task.isBlank()
                ? "Perform the task described in the agent instructions."
                : task.strip();
        return "TASK\n" + effectiveTask + "\n\nINPUT\n" + json(input);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException("Unable to construct the agent prompt", exception);
        }
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "Not specified." : value.strip();
    }
}
