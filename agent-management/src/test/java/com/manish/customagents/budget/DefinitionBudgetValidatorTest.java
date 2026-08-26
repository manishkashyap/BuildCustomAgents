package com.manish.customagents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.contracts.PromptBudget;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.error.DefinitionBudgetExceededException;
import com.manish.customagents.tool.model.CreateToolRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefinitionBudgetValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefinitionBudgetValidator validator = new DefinitionBudgetValidator(objectMapper);

    @Test
    void acceptsAToolOfOrdinarySize() {
        assertThatCode(() -> validator.checkTool(tool("Fetches a quote", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAToolWhoseModelFacingContentIsOverBudget() {
        CreateToolRequest oversized = tool("x".repeat(PromptBudget.MAX_TOOL_CHARACTERS + 1), null);

        assertThatThrownBy(() -> validator.checkTool(oversized))
                .isInstanceOf(DefinitionBudgetExceededException.class)
                .hasMessageContaining("market_get_quote")
                .hasMessageContaining("tokens");
    }

    @Test
    void countsTheOutputSchemaTowardTheToolBudget() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("description", "y".repeat(PromptBudget.MAX_TOOL_CHARACTERS));

        assertThatThrownBy(() -> validator.checkTool(tool("Fetches a quote", schema)))
                .isInstanceOf(DefinitionBudgetExceededException.class);
    }

    @Test
    void ignoresConfigurationAndExecutionPolicyBecauseTheModelNeverSeesThem() {
        CreateToolRequest tool = tool("Fetches a quote", null);
        int measured = validator.toolCharacters(objectMapper.valueToTree(tool));

        assertThat(measured)
                .isEqualTo("market_get_quote".length()
                        + "Fetches a quote".length()
                        + tool.inputSchema().toString().length());
    }

    @Test
    void rejectsAnAgentWhoseOwnFieldsAreOverBudget() {
        CreateCustomAgentRequest oversized = agent(
                "z".repeat(PromptBudget.MAX_AGENT_CHARACTERS + 1), JsonNodeFactory.instance.objectNode());

        assertThatThrownBy(() -> validator.checkAgent(oversized, List.of()))
                .isInstanceOf(DefinitionBudgetExceededException.class)
                .hasMessageContaining("Campaign QA Agent");
    }

    @Test
    void countsTheUnboundedContextFieldTowardTheAgentBudget() {
        // context has no @Size of its own; the budget is the only thing that bounds it.
        ObjectNode context = objectMapper.createObjectNode();
        context.put("catalogue", "c".repeat(PromptBudget.MAX_AGENT_CHARACTERS));

        assertThatThrownBy(() -> validator.checkAgent(agent("Review the campaign.", context), List.of()))
                .isInstanceOf(DefinitionBudgetExceededException.class);
    }

    @Test
    void rejectsAnAgentThatOnlyExceedsTheBudgetOnceItsToolsAreAdded() {
        CreateCustomAgentRequest small = agent("Review the campaign.", JsonNodeFactory.instance.objectNode());
        assertThatCode(() -> validator.checkAgent(small, List.of())).doesNotThrowAnyException();

        // Each tool is individually publishable; together they overflow the assembled request.
        JsonNode heavy = objectMapper.valueToTree(
                tool("d".repeat(PromptBudget.MAX_TOOL_CHARACTERS - 100), null));
        List<JsonNode> manyTools = java.util.Collections.nCopies(
                PromptBudget.MAX_ASSEMBLED_CHARACTERS / PromptBudget.MAX_TOOL_CHARACTERS + 1, heavy);

        assertThatThrownBy(() -> validator.checkAgent(small, manyTools))
                .isInstanceOf(DefinitionBudgetExceededException.class)
                .hasMessageContaining("with its allowed tools");
    }

    @Test
    void reportsTheOverageInCharactersAndEstimatedTokens() {
        CreateToolRequest oversized = tool("x".repeat(PromptBudget.MAX_TOOL_CHARACTERS + 1), null);

        DefinitionBudgetExceededException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                DefinitionBudgetExceededException.class, () -> validator.checkTool(oversized));

        assertThat(thrown.characters()).isGreaterThan(thrown.limit());
        assertThat(thrown.limit()).isEqualTo(PromptBudget.MAX_TOOL_CHARACTERS);
        assertThat(thrown.limitTokens()).isEqualTo(PromptBudget.MAX_TOOL_CHARACTERS / 4);
        assertThat(thrown.estimatedTokens()).isGreaterThan(thrown.limitTokens());
    }

    private CreateToolRequest tool(String description, JsonNode outputSchema) {
        return new CreateToolRequest(
                "market_get_quote",
                description,
                ToolType.HTTP,
                objectMapper.createObjectNode().put("type", "object"),
                outputSchema,
                objectMapper.createObjectNode()
                        .put("method", "GET")
                        .put("url", "https://api.example.com/quote"),
                objectMapper.createObjectNode().put("operation", "READ"));
    }

    private CreateCustomAgentRequest agent(String instructions, JsonNode context) {
        return new CreateCustomAgentRequest(
                "Campaign QA Agent",
                "Checks campaign readiness",
                "Campaign quality analyst",
                instructions,
                List.of("Do not mutate campaigns"),
                "JSON",
                null,
                context,
                List.of(),
                Set.of("market_get_quote"),
                null);
    }
}
