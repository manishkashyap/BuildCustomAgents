package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolExecutionContext(
        String runId,
        String licenseCode,
        String agentId,
        int agentVersion,
        Long toolInvocationId,
        AgentToolInvocationPort agentInvoker,
        ExecutionMode executionMode,
        Map<String, JsonNode> mockToolResults) {

    public ToolExecutionContext {
        runId = requireText(runId, "runId");
        licenseCode = requireText(licenseCode, "licenseCode");
        agentId = requireText(agentId, "agentId");
        if (agentVersion <= 0) {
            throw new IllegalArgumentException("agentVersion must be greater than zero");
        }
        agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker must not be null");
        executionMode = Objects.requireNonNull(executionMode, "executionMode must not be null");
        Map<String, JsonNode> copiedMocks = new LinkedHashMap<>();
        if (mockToolResults != null) {
            mockToolResults.forEach((name, result) -> {
                if (name == null || name.isBlank() || result == null) {
                    throw new IllegalArgumentException("mock tool names and results must not be blank or null");
                }
                copiedMocks.put(name.strip(), result.deepCopy());
            });
        }
        mockToolResults = Map.copyOf(copiedMocks);
    }

    public ToolExecutionContext(
            String runId, String licenseCode, String agentId, int agentVersion,
            Long toolInvocationId, AgentToolInvocationPort agentInvoker) {
        this(runId, licenseCode, agentId, agentVersion, toolInvocationId, agentInvoker,
                ExecutionMode.LIVE, Map.of());
    }

    public ToolExecutionContext(
            String runId, String licenseCode, String agentId, int agentVersion) {
        this(runId, licenseCode, agentId, agentVersion, null, AgentToolInvocationPort.unsupported(),
                ExecutionMode.LIVE, Map.of());
    }

    public ToolExecutionContext(
            String runId, String licenseCode, String agentId, int agentVersion,
            AgentToolInvocationPort agentInvoker) {
        this(runId, licenseCode, agentId, agentVersion, null, agentInvoker,
                ExecutionMode.LIVE, Map.of());
    }

    public static ToolExecutionContext draftTest(
            String runId, String licenseCode, String agentId, int agentVersion,
            AgentToolInvocationPort agentInvoker, Map<String, JsonNode> mockToolResults) {
        return new ToolExecutionContext(
                runId, licenseCode, agentId, agentVersion, null, agentInvoker,
                ExecutionMode.DRAFT_TEST, mockToolResults);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
