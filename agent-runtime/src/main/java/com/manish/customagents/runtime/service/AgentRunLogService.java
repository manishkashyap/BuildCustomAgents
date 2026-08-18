package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentRunCheckpointEntity;
import com.manish.customagents.runtime.entity.AgentRunTurnEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.enums.ToolInvocationStatus;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentRunCheckpointRepository;
import com.manish.customagents.runtime.repository.AgentRunTurnRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutionResult;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunLogService {

    private final AgentRunRepository runRepository;
    private final AgentRunCheckpointRepository checkpointRepository;
    private final AgentRunTurnRepository turnRepository;
    private final AgentToolInvocationRepository toolRepository;
    private final ObjectMapper objectMapper;

    public AgentRunLogService(
            AgentRunRepository runRepository,
            AgentRunCheckpointRepository checkpointRepository,
            AgentRunTurnRepository turnRepository,
            AgentToolInvocationRepository toolRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.turnRepository = turnRepository;
        this.toolRepository = toolRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(
            String runId, String licenseCode, String agentId, int agentVersion,
            String provider, String model, JsonNode input, Instant startedAt) {
        runRepository.save(new AgentRunEntity(
                runId, licenseCode, agentId, agentVersion, provider, model,
                json(input), startedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(
            String runId, String rootRunId, String parentRunId, Long parentToolInvocationId,
            String licenseCode, String requestedBy, String agentId, int agentVersion,
            String provider, String model, JsonNode input, Instant startedAt) {
        runRepository.save(new AgentRunEntity(
                runId, rootRunId, parentRunId, parentToolInvocationId,
                licenseCode, requestedBy, agentId, agentVersion, provider, model,
                json(input), startedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentToolInvocationEntity beginTool(
            String runId, int turnNumber, String toolCallId, PublishedToolDefinition tool,
            JsonNode arguments, String argumentsSha256, JsonNode approvalPolicy, Instant at) {
        return toolRepository.saveAndFlush(new AgentToolInvocationEntity(
                runId, turnNumber, toolCallId, tool.name(), tool.id(), tool.version(),
                tool.type().name(), json(arguments), argumentsSha256, json(approvalPolicy), at));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startTool(Long invocationId, Instant at) {
        AgentToolInvocationEntity invocation = requireInvocation(invocationId);
        invocation.start(at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void waitForApproval(String runId, Long invocationId, Instant at) {
        requireInvocation(invocationId).waitForApproval(at);
        requireRun(runId).waitForHuman("TOOL_APPROVAL", at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void waitForClarification(String runId, Long invocationId, Instant at) {
        requireInvocation(invocationId).waitForHuman(at);
        requireRun(runId).waitForHuman("CLARIFICATION", at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void waitForChild(String runId, Long invocationId, String childRunId, Instant at) {
        requireInvocation(invocationId).waitForChild(childRunId, at);
        requireRun(runId).waitForChild(at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeTool(
            Long invocationId, ToolExecutionResult result, long durationMs, Instant at) {
        requireInvocation(invocationId).succeed(
                json(result.output()), json(result.metadata()), durationMs, at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failTool(Long invocationId, String message, JsonNode result, long durationMs, Instant at) {
        requireInvocation(invocationId).fail(truncate(message), json(result), durationMs, at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resume(String runId, Instant at) {
        requireRun(runId).resume(at);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveCheckpoint(
            String runId, int nextTurn, Object messages, Object pendingWork,
            Object tokenUsage, Object executionScope, Instant at) {
        Optional<AgentRunCheckpointEntity> current = checkpointRepository.findById(runId);
        if (current.isPresent()) {
            current.get().update(nextTurn, json(messages), json(pendingWork),
                    json(tokenUsage), json(executionScope), at);
        } else {
            checkpointRepository.save(new AgentRunCheckpointEntity(
                    runId, nextTurn, json(messages), json(pendingWork),
                    json(tokenUsage), json(executionScope), at));
        }
    }

    @Transactional(readOnly = true)
    public AgentRunCheckpointEntity requireCheckpoint(String runId) {
        return checkpointRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Agent run checkpoint not found: " + runId));
    }

    @Transactional(readOnly = true)
    public AgentRunEntity getRun(String runId) {
        return requireRun(runId);
    }

    @Transactional(readOnly = true)
    public AgentToolInvocationEntity getInvocation(Long invocationId) {
        return requireInvocation(invocationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTurn(
            String runId, int turnNumber, BaseAgentRequest request,
            BaseAgentResponse response, Instant createdAt) {
        turnRepository.save(new AgentRunTurnEntity(
                runId, turnNumber, json(request), json(response), createdAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTool(
            String runId, int turnNumber, String toolCallId, String toolName,
            PublishedToolDefinition tool,
            JsonNode arguments, JsonNode result, ToolInvocationStatus status,
            String errorMessage, long durationMs, Instant createdAt) {
        toolRepository.save(new AgentToolInvocationEntity(
                runId, turnNumber, toolCallId, toolName,
                tool == null ? null : tool.id(),
                tool == null ? null : tool.version(),
                tool == null ? null : tool.type().name(),
                json(arguments),
                result == null ? null : json(result), status, truncate(errorMessage),
                durationMs, createdAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String runId, JsonNode output, Instant completedAt) {
        AgentRunEntity run = requireRun(runId);
        run.succeed(json(output), completedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String runId, String errorMessage, Instant completedAt) {
        AgentRunEntity run = requireRun(runId);
        run.fail(truncate(errorMessage), completedAt);
    }

    private AgentRunEntity requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Agent run log not found: " + runId));
    }

    private AgentToolInvocationEntity requireInvocation(Long invocationId) {
        return toolRepository.findById(invocationId)
                .orElseThrow(() -> new IllegalStateException("Tool invocation log not found: " + invocationId));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize the agent run log", exception);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }
}
