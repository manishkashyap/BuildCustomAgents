package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.manish.customagents.contracts.ToolType;

class ToolExecutorRegistryDraftTestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mocksNonReadToolsWithoutInvokingTheirExecutor() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor executor = executor(calls);
        PublishedToolDefinition tool = definition("WRITE");
        ToolExecutionContext context = ToolExecutionContext.draftTest(
                "test-1", "tenant-1", "agent-1", 1,
                AgentToolInvocationPort.unsupported(),
                Map.of("campaign.send", objectMapper.createObjectNode().put("status", "SIMULATED")));

        ToolExecutionResult result = new ToolExecutorRegistry(List.of(executor)).execute(
                new ToolExecutionRequest(tool, context, objectMapper.createObjectNode()));

        assertThat(calls).hasValue(0);
        assertThat(result.metadata()).containsEntry("mocked", "true");
        assertThat(result.output().path("status").asText()).isEqualTo("SIMULATED");
    }

    @Test
    void executesExplicitReadsInTestMode() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionContext context = ToolExecutionContext.draftTest(
                "test-1", "tenant-1", "agent-1", 1,
                AgentToolInvocationPort.unsupported(), Map.of());

        ToolExecutionResult result = new ToolExecutorRegistry(List.of(executor(calls))).execute(
                new ToolExecutionRequest(
                        definition("READ"), context, objectMapper.createObjectNode()));

        assertThat(calls).hasValue(1);
        assertThat(result.output().path("status").asText()).isEqualTo("REAL");
    }

    private ToolExecutor executor(AtomicInteger calls) {
        return new ToolExecutor() {
            @Override
            public ToolType type() {
                return ToolType.HTTP;
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                calls.incrementAndGet();
                return ToolExecutionResult.of(objectMapper.createObjectNode().put("status", "REAL"));
            }
        };
    }

    private PublishedToolDefinition definition(String operation) {
        return new PublishedToolDefinition(
                "tool-1", "campaign.send", "Sends a campaign", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode(),
                objectMapper.createObjectNode().put("operation", operation));
    }
}
