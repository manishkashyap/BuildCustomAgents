package com.manish.customagents.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.MessageRole;
import com.manish.customagents.runtime.model.ToolCall;

import java.util.List;
import org.junit.jupiter.api.Test;

class GenericAgentContractTest {

    @Test
    void representsAssistantToolCallsAndTheirResultsWithoutProviderTypes() {
        ToolCall toolCall = new ToolCall(
                "call-123",
                "campaign.get",
                JsonNodeFactory.instance.objectNode().put("campaignId", "cmp-123"));

        AgentMessage assistant = AgentMessage.assistant("", List.of(toolCall));
        AgentMessage result = AgentMessage.toolResult(
                "call-123",
                "campaign.get",
                "{\"status\":\"ACTIVE\"}");

        assertThat(assistant.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistant.toolCalls()).containsExactly(toolCall);
        assertThat(result.role()).isEqualTo(MessageRole.TOOL);
        assertThat(result.toolCallId()).isEqualTo("call-123");
    }

    @Test
    void rejectsToolArgumentsThatAreNotAJsonObject() {
        assertThatThrownBy(() -> new ToolCall(
                "call-123",
                "campaign.get",
                JsonNodeFactory.instance.arrayNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }
}
