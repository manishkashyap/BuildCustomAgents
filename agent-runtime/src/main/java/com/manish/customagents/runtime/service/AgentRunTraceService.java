package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentRunTurnEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.errors.AgentRunNotFoundException;
import com.manish.customagents.runtime.model.AgentRunTraceResponse;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentRunTurnRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the read-only turn-by-turn trace of a root run. Nothing here mutates run state: it
 * reads the rows execution already wrote, so calling it never disturbs an in-flight run.
 */
@Service
public class AgentRunTraceService {

    private final AgentRunRepository runRepository;
    private final AgentRunTurnRepository turnRepository;
    private final AgentToolInvocationRepository invocationRepository;
    private final ObjectMapper objectMapper;

    public AgentRunTraceService(
            AgentRunRepository runRepository,
            AgentRunTurnRepository turnRepository,
            AgentToolInvocationRepository invocationRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.turnRepository = turnRepository;
        this.invocationRepository = invocationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AgentRunTraceResponse trace(String licenseCode, String runId) {
        AgentRunEntity requested = runRepository.findByIdAndLicenseCode(runId, licenseCode)
                .orElseThrow(() -> new AgentRunNotFoundException("Agent run not found: " + runId));

        // Any run in the tree traces the whole tree, so a child run id in the UI still shows context.
        String rootRunId = requested.getRootRunId() == null ? requested.getId() : requested.getRootRunId();
        List<AgentRunEntity> runs = new ArrayList<>(runRepository.findByRootRunId(rootRunId));
        if (runs.stream().noneMatch(run -> run.getId().equals(rootRunId))) {
            runRepository.findByIdAndLicenseCode(rootRunId, licenseCode).ifPresent(runs::add);
        }
        runs.removeIf(run -> !licenseCode.equals(run.getLicenseCode()));
        if (runs.isEmpty()) {
            throw new AgentRunNotFoundException("Agent run not found: " + runId);
        }

        List<String> runIds = runs.stream().map(AgentRunEntity::getId).toList();
        Map<String, List<AgentRunTurnEntity>> turnsByRun = new HashMap<>();
        turnRepository.findByRunIdInOrderByRunIdAscTurnNumberAsc(runIds)
                .forEach(turn -> turnsByRun.computeIfAbsent(turn.getRunId(), key -> new ArrayList<>()).add(turn));
        Map<String, List<AgentToolInvocationEntity>> invocationsByRun = new HashMap<>();
        invocationRepository.findByRunIdInOrderByRunIdAscTurnNumberAscIdAsc(runIds)
                .forEach(inv -> invocationsByRun.computeIfAbsent(inv.getRunId(), key -> new ArrayList<>()).add(inv));

        Map<String, Integer> depths = depths(runs, rootRunId);
        List<AgentRunTraceResponse.TracedRun> traced = runs.stream()
                .sorted(Comparator
                        .comparingInt((AgentRunEntity run) -> depths.getOrDefault(run.getId(), 0))
                        .thenComparing(AgentRunEntity::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgentRunEntity::getId))
                .map(run -> tracedRun(
                        run,
                        depths.getOrDefault(run.getId(), 0),
                        turnsByRun.getOrDefault(run.getId(), List.of()),
                        invocationsByRun.getOrDefault(run.getId(), List.of())))
                .toList();

        int turnCount = traced.stream().mapToInt(run -> run.turns().size()).sum();
        return new AgentRunTraceResponse(rootRunId, sum(traced), turnCount, traced);
    }

    private AgentRunTraceResponse.TracedRun tracedRun(
            AgentRunEntity run,
            int depth,
            List<AgentRunTurnEntity> turns,
            List<AgentToolInvocationEntity> invocations) {
        Map<Integer, List<AgentToolInvocationEntity>> invocationsByTurn = new HashMap<>();
        invocations.forEach(inv -> invocationsByTurn
                .computeIfAbsent(inv.getTurnNumber(), key -> new ArrayList<>()).add(inv));

        List<AgentRunTraceResponse.TracedTurn> tracedTurns = turns.stream()
                .map(turn -> tracedTurn(turn, invocationsByTurn.getOrDefault(turn.getTurnNumber(), List.of())))
                .toList();

        // A run's usage is the sum of its own turns; child runs report their own totals separately.
        TokenUsage usage = accumulate(tracedTurns.stream().map(AgentRunTraceResponse.TracedTurn::usage).toList());
        return new AgentRunTraceResponse.TracedRun(
                run.getId(),
                run.getParentRunId(),
                run.getAgentId(),
                run.getAgentVersion(),
                depth,
                run.getStatus(),
                run.getProvider(),
                run.getModel(),
                readJson(run.getInputJson()),
                readJson(run.getOutputJson()),
                run.getErrorMessage(),
                usage,
                run.getStartedAt(),
                run.getLastActivityAt(),
                run.getCompletedAt(),
                tracedTurns);
    }

    private AgentRunTraceResponse.TracedTurn tracedTurn(
            AgentRunTurnEntity turn, List<AgentToolInvocationEntity> invocations) {
        JsonNode request = readJson(turn.getRequestJson());
        JsonNode response = readJson(turn.getResponseJson());
        return new AgentRunTraceResponse.TracedTurn(
                turn.getTurnNumber(),
                arrayAt(request, "messages"),
                text(response, "text"),
                arrayAt(response, "toolCalls"),
                text(response, "finishReason"),
                usageAt(response),
                turn.getCreatedAt(),
                invocations.stream().map(this::tracedInvocation).toList());
    }

    /** Always an array node, so a caller never has to distinguish "absent" from "empty". */
    private JsonNode arrayAt(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value != null && value.isArray() ? value : objectMapper.createArrayNode();
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Read usage field by field rather than binding TokenUsage, whose invariant (total at least
     * input plus output) some providers violate in their own reported numbers.
     */
    private TokenUsage usageAt(JsonNode response) {
        JsonNode usage = response == null ? null : response.get("usage");
        if (usage == null || !usage.isObject()) {
            return TokenUsage.ZERO;
        }
        int input = Math.max(0, usage.path("inputTokens").asInt(0));
        int output = Math.max(0, usage.path("outputTokens").asInt(0));
        int total = Math.max(0, usage.path("totalTokens").asInt(0));
        return new TokenUsage(input, output, Math.max(total, input + output));
    }

    private AgentRunTraceResponse.TracedToolInvocation tracedInvocation(AgentToolInvocationEntity invocation) {
        return new AgentRunTraceResponse.TracedToolInvocation(
                invocation.getId(),
                invocation.getToolCallId(),
                invocation.getToolName(),
                invocation.getToolType(),
                invocation.getToolVersion(),
                invocation.getStatus(),
                readJson(invocation.getArgumentsJson()),
                readJson(invocation.getResultJson()),
                invocation.getChildRunId(),
                invocation.getErrorMessage(),
                invocation.getDurationMs(),
                invocation.getCreatedAt());
    }

    private Map<String, Integer> depths(List<AgentRunEntity> runs, String rootRunId) {
        Map<String, String> parents = new HashMap<>();
        runs.forEach(run -> parents.put(run.getId(), run.getParentRunId()));
        Map<String, Integer> depths = new HashMap<>();
        runs.forEach(run -> depths.put(run.getId(), depth(run.getId(), rootRunId, parents)));
        return depths;
    }

    private int depth(String runId, String rootRunId, Map<String, String> parents) {
        int depth = 0;
        String current = runId;
        // Bounded by the number of known runs, so a malformed parent cycle cannot spin here.
        while (current != null && !current.equals(rootRunId) && depth <= parents.size()) {
            current = parents.get(current);
            depth++;
        }
        return depth;
    }

    private TokenUsage sum(List<AgentRunTraceResponse.TracedRun> runs) {
        return accumulate(runs.stream().map(AgentRunTraceResponse.TracedRun::usage).toList());
    }

    private TokenUsage accumulate(List<TokenUsage> usages) {
        int input = 0;
        int output = 0;
        int total = 0;
        for (TokenUsage usage : usages) {
            if (usage == null) {
                continue;
            }
            input += usage.inputTokens();
            output += usage.outputTokens();
            total += usage.totalTokens();
        }
        // Providers sometimes report a total below their own input plus output; keep TokenUsage valid.
        return new TokenUsage(input, output, Math.max(total, input + output));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
