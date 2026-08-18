package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CustomAgentToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void delegatesThroughInvocationPortAndReturnsItsResult() {
        AtomicReference<AgentToolInvocationRequest> captured = new AtomicReference<>();
        ToolExecutionContext context = new ToolExecutionContext(
                "run-parent",
                "tenant-1",
                "parent-agent",
                1,
                request -> {
                    captured.set(request);
                    return new ToolExecutionResult(
                            objectMapper.createObjectNode().put("answer", "child result"),
                            Map.of("childRunId", "run-child"));
                });
        PublishedToolDefinition tool = new PublishedToolDefinition(
                "tool-1",
                "child.research",
                "Delegates research",
                ToolType.CUSTOM_AGENT,
                1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode()
                        .put("agentId", "child-agent")
                        .put("agentVersion", 4)
                        .set("settings", objectMapper.createObjectNode().put("failurePolicy", "PROPAGATE")),
                objectMapper.createObjectNode());
        ToolExecutionRequest request = new ToolExecutionRequest(
                tool,
                context,
                objectMapper.createObjectNode()
                        .put("task", "Research the audience")
                        .set("input", objectMapper.createObjectNode().put("campaignId", "cmp-1")));

        ToolExecutionResult result = new ToolExecutorRegistry(List.of(new CustomAgentToolExecutor()))
                .execute(request);

        assertThat(result.output().path("answer").asText()).isEqualTo("child result");
        assertThat(result.metadata()).containsEntry("childRunId", "run-child");
        assertThat(captured.get().childAgentId()).isEqualTo("child-agent");
        assertThat(captured.get().childAgentVersion()).isEqualTo(4);
        assertThat(captured.get().task()).isEqualTo("Research the audience");
        assertThat(captured.get().settings().path("failurePolicy").asText())
                .isEqualTo("PROPAGATE");
    }
}
