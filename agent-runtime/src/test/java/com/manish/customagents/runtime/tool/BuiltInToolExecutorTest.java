package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import org.junit.jupiter.api.Test;
import com.manish.customagents.contracts.ToolType;

class BuiltInToolExecutorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void producesStructuredClarificationSuspension() {
        PublishedToolDefinition definition = new PublishedToolDefinition(
                "runtime-request-clarification", "request_clarification", "Asks a human",
                ToolType.BUILT_IN, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
        ToolExecutionResult result = new BuiltInToolExecutor().execute(new ToolExecutionRequest(
                definition,
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode()
                        .put("category", "MISSING_TASK_INPUT")
                        .put("question", "Which segment?")
                        .put("reason", "Three segments are eligible")
                        .put("responseType", "SINGLE_SELECT")));

        assertThat(result.isWaitingForHuman()).isTrue();
        assertThat(result.humanInteraction().type()).isEqualTo(HumanInteractionType.CLARIFICATION);
        assertThat(result.humanInteraction().responseType()).isEqualTo(HumanResponseType.SINGLE_SELECT);
        assertThat(result.humanInteraction().question()).isEqualTo("Which segment?");
    }
}
