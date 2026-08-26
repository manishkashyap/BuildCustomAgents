package com.manish.customagents.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.agent.model.AgentExample;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.contracts.PromptBudget;
import com.manish.customagents.error.DefinitionBudgetExceededException;
import com.manish.customagents.tool.model.CreateToolRequest;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Measures how much of a model request a definition will occupy, and refuses to publish one
 * that no longer fits.
 *
 * <p>Counted content is exactly what leaves for the provider: for a tool, the name, description
 * and the two schemas; for an agent, every prompt field plus the response schema it sends as a
 * structured-output constraint. A tool's configuration and execution policy are read by the
 * runtime and never sent, so charging an author for an HTTP URL they cannot shorten would be
 * noise.
 *
 * <p>Tools are measured from their stored JSON rather than their request record, because the
 * agent publish path resolves dependencies as rows and should not have to rebuild each one.
 *
 * <p>Measuring characters rather than tokenising keeps this dependency-free and provider
 * agnostic. The budgets in {@link PromptBudget} carry enough slack that the estimate's error
 * cannot turn a reasonable definition into a rejection.
 */
@Component
public class DefinitionBudgetValidator {

    private final ObjectMapper objectMapper;

    public DefinitionBudgetValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Throws when one tool's model-facing content is over budget. */
    public void checkTool(CreateToolRequest tool) {
        checkTool(tool.name(), objectMapper.valueToTree(tool));
    }

    /** Throws when one stored tool definition is over budget. */
    public void checkTool(String toolName, JsonNode storedDefinition) {
        require(PromptBudget.forTool(toolName, toolCharacters(storedDefinition)));
    }

    /** Throws when the agent alone, or the agent together with all of its tools, is over budget. */
    public void checkAgent(CreateCustomAgentRequest agent, Collection<JsonNode> storedTools) {
        int agentCharacters = agentCharacters(agent);
        require(PromptBudget.forAgent(agent.name(), agentCharacters));
        int assembled = agentCharacters;
        for (JsonNode tool : storedTools) {
            assembled += toolCharacters(tool);
        }
        require(PromptBudget.forAssembledPrompt(agent.name(), assembled));
    }

    public int toolCharacters(JsonNode storedDefinition) {
        return text(storedDefinition.path("name"))
                + text(storedDefinition.path("description"))
                + json(storedDefinition.path("inputSchema"))
                + json(storedDefinition.path("outputSchema"));
    }

    public int agentCharacters(CreateCustomAgentRequest agent) {
        int total = length(agent.name())
                + length(agent.description())
                + length(agent.role())
                + length(agent.instructions())
                + length(agent.outputFormat())
                + json(agent.outputSchema())
                + json(agent.context());
        for (String rule : agent.rules()) {
            total += length(rule);
        }
        for (AgentExample example : agent.examples()) {
            total += length(example.description())
                    + json(example.input())
                    + json(example.expectedOutput());
        }
        for (String toolName : agent.allowedTools()) {
            total += length(toolName);
        }
        return total;
    }

    private void require(PromptBudget.Usage usage) {
        if (usage.exceeded()) {
            throw new DefinitionBudgetExceededException(usage);
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private int text(JsonNode value) {
        return value == null ? 0 : value.asText("").length();
    }

    private int json(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull() ? 0 : value.toString().length();
    }
}
