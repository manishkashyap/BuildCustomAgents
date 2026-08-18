package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.config.AgentExecutionProperties;
import com.manish.customagents.runtime.definition.ManagementAgentDefinitionRepository;
import com.manish.customagents.runtime.definition.ManagementToolDefinitionRepository;
import com.manish.customagents.runtime.definition.PublishedAgentDefinition;
import com.manish.customagents.runtime.definition.StoredAgentDefinition;
import com.manish.customagents.runtime.entity.AgentRunCheckpointEntity;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.entity.HumanInteractionRequestEntity;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.GenerationOptions;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;
import com.manish.customagents.runtime.model.PendingInteractionSummary;
import com.manish.customagents.runtime.model.RunAgentRequest;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import com.manish.customagents.runtime.tool.AgentToolInvocationRequest;
import com.manish.customagents.runtime.tool.BuiltInToolExecutor;
import com.manish.customagents.runtime.tool.HumanInteractionRequestSpec;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutionContext;
import com.manish.customagents.runtime.tool.ToolExecutionRequest;
import com.manish.customagents.runtime.tool.ToolExecutionResult;
import com.manish.customagents.runtime.tool.ToolExecutorRegistry;
import com.manish.customagents.runtime.tool.ToolType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionService {
    private static final TypeReference<List<AgentMessage>> MESSAGE_LIST = new TypeReference<>() {};

    private final ManagementAgentDefinitionRepository agentRepository;
    private final ManagementToolDefinitionRepository toolDefinitionRepository;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final BaseAgent baseAgent;
    private final AgentPromptBuilder promptBuilder;
    private final AgentRunLogService logStore;
    private final HumanInteractionService humanInteractions;
    private final AgentRunRepository runRepository;
    private final AgentToolInvocationRepository invocationRepository;
    private final AgentExecutionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentExecutionService(
            ManagementAgentDefinitionRepository agentRepository,
            ManagementToolDefinitionRepository toolDefinitionRepository,
            ToolExecutorRegistry toolExecutorRegistry,
            BaseAgent baseAgent,
            AgentPromptBuilder promptBuilder,
            AgentRunLogService logStore,
            HumanInteractionService humanInteractions,
            AgentRunRepository runRepository,
            AgentToolInvocationRepository invocationRepository,
            AgentExecutionProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.agentRepository = agentRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.baseAgent = baseAgent;
        this.promptBuilder = promptBuilder;
        this.logStore = logStore;
        this.humanInteractions = humanInteractions;
        this.runRepository = runRepository;
        this.invocationRepository = invocationRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AgentRunResponse run(String licenseCode, RunAgentRequest command) {
        return run(licenseCode, command, "local-requester");
    }

    public AgentRunResponse run(String licenseCode, RunAgentRequest command, String requestedBy) {
        ModelSelection model = command.modelSelectionOr(properties.defaultModelSelection());
        ExecutionScope scope = new ExecutionScope(
                null, null, null, 0, List.of(), properties.getMaxAgentInvocations(), requestedBy);
        return startAgent(licenseCode, command.agentId(), null, command.task(), command.input(), model, scope);
    }

    public AgentRunResponse get(String licenseCode, String runId) {
        AgentRunEntity run = runRepository.findByIdAndLicenseCode(runId, licenseCode)
                .orElseThrow(() -> new AgentExecutionException("Agent run not found: " + runId));
        return response(run, parseNullable(run.getOutputJson()), TokenUsage.ZERO);
    }

    public void resumeFromInteractions(String rootRunId, List<String> interactionIds) {
        if (interactionIds == null || interactionIds.isEmpty()) return;
        List<HumanInteractionService.ResumeTarget> targets =
                humanInteractions.resumeTargets(rootRunId, interactionIds);
        Map<String, AgentRunEntity> runs = new HashMap<>();
        runRepository.findByRootRunId(rootRunId).forEach(run -> runs.put(run.getId(), run));
        AgentRunEntity root = runRepository.findById(rootRunId)
                .orElseThrow(() -> new AgentExecutionException("Root agent run not found: " + rootRunId));
        runs.putIfAbsent(root.getId(), root);

        List<HumanInteractionService.ResumeTarget> ordered = deepestFirst(targets, runs);
        Set<String> resumedRuns = new HashSet<>();
        for (HumanInteractionService.ResumeTarget target : ordered) {
            if (!resumedRuns.add(target.runId()) || humanInteractions.hasPendingForRun(target.runId())) {
                continue;
            }
            AgentRunEntity run = requireRunInRoot(target.runId(), runs);
            if (run.isTerminal()) continue;
            String checkpointInteraction = checkpointInteractionId(run.getId());
            if (checkpointInteraction != null && !checkpointInteraction.equals(target.interactionId())) {
                HumanInteractionService.ResumeTarget matching = ordered.stream()
                        .filter(candidate -> candidate.runId().equals(run.getId()))
                        .filter(candidate -> candidate.interactionId().equals(checkpointInteraction))
                        .findFirst().orElse(null);
                if (matching == null) continue;
                resumeFromInteraction(run.getId(), matching.interactionId());
            } else {
                resumeFromInteraction(run.getId(), target.interactionId());
            }
        }
    }

    List<HumanInteractionService.ResumeTarget> deepestFirst(
            List<HumanInteractionService.ResumeTarget> targets,
            Map<String, AgentRunEntity> runs) {
        return targets.stream()
                .sorted(Comparator
                        .comparingInt((HumanInteractionService.ResumeTarget target) ->
                                runDepth(requireRunInRoot(target.runId(), runs), runs))
                        .reversed()
                        .thenComparing(HumanInteractionService.ResumeTarget::interactionId))
                .toList();
    }

    public void resumeFromInteraction(String runId, String interactionId) {
        AgentRunEntity run = logStore.getRun(runId);
        if (run.isTerminal()) return;
        try {
            resumeFromInteraction(run, interactionId);
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            logStore.fail(run.getId(), safeMessage(exception), failedAt);
            if (!run.getRootRunId().equals(run.getId())) {
                AgentRunEntity root = logStore.getRun(run.getRootRunId());
                if (!root.isTerminal()) {
                    logStore.fail(root.getId(), safeMessage(exception), failedAt);
                }
            }
        }
    }

    private void resumeFromInteraction(AgentRunEntity run, String interactionId) {
        String runId = run.getId();
        HumanInteractionService.ResolvedInteraction resolution =
                humanInteractions.resolved(run.getLicenseCode(), interactionId);
        AgentRunCheckpointEntity checkpoint = logStore.requireCheckpoint(runId);
        List<AgentMessage> messages = read(checkpoint.getMessagesJson(), MESSAGE_LIST);
        PendingWork pending = read(checkpoint.getPendingWorkJson(), PendingWork.class);
        if (pending.interactionId() != null && !pending.interactionId().equals(interactionId)) {
            throw new AgentExecutionException(
                    "Interaction does not match the durable checkpoint for run " + runId);
        }
        TokenUsage usage = read(checkpoint.getTokenUsageJson(), TokenUsage.class);
        ExecutionScope scope = read(checkpoint.getExecutionScopeJson(), ExecutionScope.class);

        logStore.resume(runId, clock.instant());
        AgentToolInvocationEntity invocation = logStore.getInvocation(pending.invocationId());
        ToolProgress progress;
        if (resolution.type() == HumanInteractionType.CLARIFICATION) {
            ObjectNode answer = objectMapper.createObjectNode()
                    .put("status", "ANSWERED")
                    .put("interactionId", interactionId)
                    .put("answeredAt", resolution.resolvedAt().toString())
                    .set("answer", resolution.answer());
            ToolExecutionResult result = ToolExecutionResult.of(answer);
            logStore.completeTool(invocation.getId(), result, 0, clock.instant());
            progress = ToolProgress.completed(answer);
        } else if (resolution.action() == HumanResponseAction.REJECT) {
            progress = ToolProgress.completed(parseNullable(invocation.getResultJson()));
        } else {
            PublishedToolDefinition tool = resolveTool(run.getLicenseCode(), invocation.getToolName());
            verifyBinding(invocation, tool);
            ToolCall call = pending.calls().get(pending.callIndex());
            progress = dispatchExisting(run, scope, invocation, tool, call);
        }

        if (progress.waiting()) return;
        messages.add(AgentMessage.toolResult(
                pending.calls().get(pending.callIndex()).id(),
                pending.calls().get(pending.callIndex()).name(),
                writeJson(progress.output())));
        AgentRunResponse outcome = continueRemainingCallsThenRun(
                run, scope, messages, pending, usage, pending.callIndex() + 1);
        if (outcome.status() == AgentRunStatus.SUCCEEDED) propagateToParent(outcome);
    }

    private String checkpointInteractionId(String runId) {
        AgentRunCheckpointEntity checkpoint = logStore.requireCheckpoint(runId);
        return read(checkpoint.getPendingWorkJson(), PendingWork.class).interactionId();
    }

    private AgentRunEntity requireRunInRoot(String runId, Map<String, AgentRunEntity> runs) {
        AgentRunEntity run = runs.get(runId);
        if (run == null) {
            throw new AgentExecutionException("Run " + runId + " does not belong to the requested root");
        }
        return run;
    }

    private int runDepth(AgentRunEntity run, Map<String, AgentRunEntity> runs) {
        int depth = 0;
        Set<String> visited = new HashSet<>();
        AgentRunEntity current = run;
        while (current.getParentRunId() != null) {
            if (!visited.add(current.getId())) {
                throw new AgentExecutionException("Cycle detected in persisted agent-run ancestry");
            }
            current = requireRunInRoot(current.getParentRunId(), runs);
            depth++;
        }
        return depth;
    }

    private AgentRunResponse startAgent(
            String licenseCode, String agentId, Integer expectedVersion, String task, JsonNode input,
            ModelSelection model, ExecutionScope parentScope) {
        if (parentScope.ancestry().contains(agentId)) {
            throw new AgentExecutionException("Custom-agent invocation cycle detected: "
                    + String.join(" -> ", parentScope.ancestry()) + " -> " + agentId);
        }
        if (parentScope.depth() > properties.getMaxAgentDepth()) {
            throw new AgentExecutionException(
                    "Custom-agent invocation exceeded maximum depth " + properties.getMaxAgentDepth());
        }
        StoredAgentDefinition agent = agentRepository.get(licenseCode, agentId);
        if (expectedVersion != null && agent.version() != expectedVersion) {
            throw new AgentExecutionException("Published custom agent " + agentId + " is version "
                    + agent.version() + " but tool requires version " + expectedVersion);
        }

        String runId = UUID.randomUUID().toString();
        String rootRunId = parentScope.rootRunId() == null ? runId : parentScope.rootRunId();
        List<String> ancestry = new ArrayList<>(parentScope.ancestry());
        ancestry.add(agent.id());
        ExecutionScope scope = new ExecutionScope(
                rootRunId, parentScope.parentRunId(), parentScope.parentToolInvocationId(),
                parentScope.depth(), List.copyOf(ancestry), parentScope.remainingInvocations(),
                parentScope.requestedBy());
        Instant startedAt = clock.instant();
        logStore.start(runId, rootRunId, parentScope.parentRunId(), parentScope.parentToolInvocationId(),
                licenseCode, parentScope.requestedBy(), agent.id(), agent.version(),
                model.provider().name(), model.model(), runInput(task, input), startedAt);
        AgentRunEntity run = logStore.getRun(runId);
        try {
            // Close the race with management retirement: a starter that read PUBLISHED before
            // RETIRING was committed must re-check after its run row is visible to eligibility checks.
            StoredAgentDefinition confirmed = agentRepository.get(licenseCode, agentId);
            if (confirmed.version() != agent.version()) {
                throw new AgentExecutionException("Custom agent changed while the run was starting");
            }
            List<AgentMessage> messages = new ArrayList<>(
                    promptBuilder.build(confirmed.definition(), task, input));
            return continueRun(run, confirmed, model, scope, messages, 1, TokenUsage.ZERO);
        } catch (RuntimeException exception) {
            logStore.fail(runId, safeMessage(exception), clock.instant());
            throw exception;
        }
    }

    private AgentRunResponse continueRun(
            AgentRunEntity run, StoredAgentDefinition agent, ModelSelection model, ExecutionScope scope,
            List<AgentMessage> messages, int startTurn, TokenUsage initialUsage) {
        List<PublishedToolDefinition> allowedTools = allowedTools(run.getLicenseCode(), agent);
        Map<String, PublishedToolDefinition> toolsByName = index(allowedTools);
        TokenCounter tokens = new TokenCounter(initialUsage);

        for (int turn = startTurn; turn <= properties.getMaxTurns(); turn++) {
            BaseAgentRequest request = new BaseAgentRequest(
                    model, messages, allowedTools.stream().map(PublishedToolDefinition::toLlmDefinition).toList(),
                    new GenerationOptions(null, null, true, agent.definition().outputSchema()),
                    Map.of("rootRunId", run.getRootRunId(), "runId", run.getId(),
                            "licenseCode", run.getLicenseCode(), "agentId", agent.id(),
                            "agentVersion", Integer.toString(agent.version()),
                            "invocationDepth", Integer.toString(scope.depth())));
            BaseAgentResponse llmResponse = baseAgent.generate(request);
            logStore.recordTurn(run.getId(), turn, request, llmResponse, clock.instant());
            tokens.add(llmResponse.usage());
            messages.add(llmResponse.toAssistantMessage());

            if (!llmResponse.toolCalls().isEmpty()) {
                PendingWork work = new PendingWork(
                        "TOOL_CALLS", null, turn, llmResponse.toolCalls(), 0, null, null);
                AgentRunResponse result = processCalls(
                        run, agent, model, scope, messages, tokens.value(), toolsByName, work, 0);
                if (result != null) return result;
                continue;
            }
            if (llmResponse.finishReason() == FinishReason.STOP) {
                JsonNode output = output(llmResponse.text());
                logStore.succeed(run.getId(), output, clock.instant());
                return response(logStore.getRun(run.getId()), output, tokens.value());
            }
            throw new AgentExecutionException(
                    "Generic agent stopped without a final response: " + llmResponse.finishReason());
        }
        throw new AgentExecutionException(
                "Custom agent exceeded the maximum of " + properties.getMaxTurns() + " turns");
    }

    private AgentRunResponse processCalls(
            AgentRunEntity run, StoredAgentDefinition agent, ModelSelection model, ExecutionScope scope,
            List<AgentMessage> messages, TokenUsage usage,
            Map<String, PublishedToolDefinition> toolsByName, PendingWork work, int fromIndex) {
        for (int index = fromIndex; index < work.calls().size(); index++) {
            ToolCall call = work.calls().get(index);
            PublishedToolDefinition tool = toolsByName.get(call.name());
            if (tool == null) throw new AgentExecutionException("Tool call is not allowed: " + call.name());
            AgentToolInvocationEntity invocation = logStore.beginTool(
                    run.getId(), work.turnNumber(), call.id(), tool, call.arguments(),
                    binding(tool, call.arguments()), approvalPolicy(tool), clock.instant());
            if (requiresApproval(tool)) {
                HumanInteractionRequestSpec spec = approvalRequest(tool, call);
                HumanInteractionRequestEntity interaction = humanInteractions.create(
                        run, invocation, spec, invocation.getArgumentsSha256());
                logStore.waitForApproval(run.getId(), invocation.getId(), clock.instant());
                saveCheckpoint(run.getId(), work.turnNumber() + 1, messages,
                        work.withPending(invocation.getId(), index, interaction.getId(), null),
                        usage, scope);
                return waitingResponse(run);
            }
            logStore.startTool(invocation.getId(), clock.instant());
            ToolProgress progress = dispatch(run, agent, model, scope, invocation, tool, call);
            if (progress.waiting()) {
                saveCheckpoint(run.getId(), work.turnNumber() + 1, messages,
                        work.withPending(invocation.getId(), index, progress.interactionId(), progress.childRunId()),
                        usage, scope);
                return waitingResponse(run);
            }
            messages.add(AgentMessage.toolResult(call.id(), call.name(), writeJson(progress.output())));
        }
        return null;
    }

    private ToolProgress dispatch(
            AgentRunEntity run, StoredAgentDefinition agent, ModelSelection model, ExecutionScope scope,
            AgentToolInvocationEntity invocation, PublishedToolDefinition tool, ToolCall call) {
        Instant startedAt = clock.instant();
        try {
            ToolExecutionContext context = new ToolExecutionContext(
                    run.getId(), run.getLicenseCode(), agent.id(), agent.version(), invocation.getId(),
                    child -> invokeChildAgent(child, model, scope, run.getId(), invocation.getId()));
            ToolExecutionResult execution = toolExecutorRegistry.execute(
                    new ToolExecutionRequest(tool, context, call.arguments()));
            if (execution.isWaitingForHuman()) {
                HumanInteractionRequestSpec effectiveSpec = effectiveClarificationPolicy(
                        agent.definition(), execution.humanInteraction());
                HumanInteractionRequestEntity interaction = humanInteractions.create(
                        run, invocation, effectiveSpec, invocation.getArgumentsSha256());
                logStore.waitForClarification(run.getId(), invocation.getId(), clock.instant());
                return ToolProgress.waitingForHuman(interaction.getId());
            }
            if (execution.isWaitingForChild()) {
                logStore.waitForChild(run.getId(), invocation.getId(), execution.waitingChildRunId(), clock.instant());
                return ToolProgress.waitingForChild(execution.waitingChildRunId());
            }
            logStore.completeTool(invocation.getId(), execution, elapsedMillis(startedAt), clock.instant());
            return ToolProgress.completed(execution.output());
        } catch (RuntimeException exception) {
            ObjectNode error = objectMapper.createObjectNode()
                    .put("error", safeMessage(exception)).put("tool", call.name());
            logStore.failTool(invocation.getId(), safeMessage(exception), error,
                    elapsedMillis(startedAt), clock.instant());
            return ToolProgress.completed(error);
        }
    }

    private ToolProgress dispatchExisting(
            AgentRunEntity run, ExecutionScope scope, AgentToolInvocationEntity invocation,
            PublishedToolDefinition tool, ToolCall call) {
        StoredAgentDefinition agent = agentRepository.getForExistingRun(
                run.getLicenseCode(), run.getAgentId(), run.getAgentVersion());
        logStore.startTool(invocation.getId(), clock.instant());
        ModelSelection model = new ModelSelection(ModelProvider.valueOf(run.getProvider()), run.getModel());
        return dispatch(run, agent, model, scope, invocation, tool, call);
    }

    private ToolExecutionResult invokeChildAgent(
            AgentToolInvocationRequest invocation, ModelSelection model, ExecutionScope parentScope,
            String parentRunId, Long parentInvocationId) {
        if (parentScope.remainingInvocations() <= 0) {
            throw new AgentExecutionException("Custom-agent invocation exceeded invocation budget");
        }
        if (runRepository.countByRootRunId(parentScope.rootRunId()) - 1
                >= properties.getMaxAgentInvocations()) {
            throw new AgentExecutionException("Custom-agent invocation exceeded invocation budget");
        }
        ExecutionScope childScope = new ExecutionScope(
                parentScope.rootRunId(), parentRunId, parentInvocationId, parentScope.depth() + 1,
                parentScope.ancestry(), parentScope.remainingInvocations() - 1, parentScope.requestedBy());
        AgentRunResponse child = startAgent(
                invocation.parentContext().licenseCode(), invocation.childAgentId(),
                invocation.childAgentVersion(), invocation.task(), invocation.input(), model, childScope);
        if (child.status() == AgentRunStatus.WAITING_FOR_HUMAN) {
            return ToolExecutionResult.waitingForChild(child.runId());
        }
        ObjectNode result = objectMapper.createObjectNode()
                .put("status", child.status().name()).set("output", child.output());
        return new ToolExecutionResult(result, Map.of(
                "childRunId", child.runId(), "childAgentId", child.agentId(),
                "childAgentVersion", Integer.toString(child.agentVersion())));
    }

    private AgentRunResponse continueRemainingCallsThenRun(
            AgentRunEntity run, ExecutionScope scope, List<AgentMessage> messages,
            PendingWork work, TokenUsage usage, int nextCallIndex) {
        StoredAgentDefinition agent = agentRepository.getForExistingRun(
                run.getLicenseCode(), run.getAgentId(), run.getAgentVersion());
        ModelSelection model = new ModelSelection(ModelProvider.valueOf(run.getProvider()), run.getModel());
        Map<String, PublishedToolDefinition> tools = index(allowedTools(run.getLicenseCode(), agent));
        AgentRunResponse waiting = processCalls(
                run, agent, model, scope, messages, usage, tools, work, nextCallIndex);
        if (waiting != null) return waiting;
        return continueRun(run, agent, model, scope, messages, work.turnNumber() + 1, usage);
    }

    private void propagateToParent(AgentRunResponse child) {
        AgentRunEntity childRun = logStore.getRun(child.runId());
        if (childRun.getParentRunId() == null || childRun.getParentToolInvocationId() == null) return;
        AgentRunEntity parent = logStore.getRun(childRun.getParentRunId());
        if (parent.isTerminal()) return;
        AgentToolInvocationEntity invocation = logStore.getInvocation(childRun.getParentToolInvocationId());
        ObjectNode resultNode = objectMapper.createObjectNode()
                .put("status", "SUCCEEDED").set("output", child.output());
        logStore.completeTool(invocation.getId(), ToolExecutionResult.of(resultNode), 0, clock.instant());
        AgentRunCheckpointEntity checkpoint = logStore.requireCheckpoint(parent.getId());
        List<AgentMessage> messages = read(checkpoint.getMessagesJson(), MESSAGE_LIST);
        PendingWork pending = read(checkpoint.getPendingWorkJson(), PendingWork.class);
        TokenUsage usage = read(checkpoint.getTokenUsageJson(), TokenUsage.class);
        ExecutionScope scope = read(checkpoint.getExecutionScopeJson(), ExecutionScope.class);
        logStore.resume(parent.getId(), clock.instant());
        ToolCall call = pending.calls().get(pending.callIndex());
        messages.add(AgentMessage.toolResult(call.id(), call.name(), writeJson(resultNode)));
        AgentRunResponse parentOutcome = continueRemainingCallsThenRun(
                parent, scope, messages, pending, usage, pending.callIndex() + 1);
        if (parentOutcome.status() == AgentRunStatus.SUCCEEDED) propagateToParent(parentOutcome);
    }

    private List<PublishedToolDefinition> allowedTools(String licenseCode, StoredAgentDefinition agent) {
        List<PublishedToolDefinition> resolved = new ArrayList<>(toolDefinitionRepository.resolve(
                licenseCode, agent.definition().allowedTools()));
        resolved.add(clarificationTool());
        return List.copyOf(resolved);
    }

    private PublishedToolDefinition clarificationTool() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode propertiesNode = objectMapper.createObjectNode();
        propertiesNode.set("category", type("string"));
        propertiesNode.set("question", type("string"));
        propertiesNode.set("reason", type("string"));
        propertiesNode.set("responseType", type("string"));
        propertiesNode.set("audienceHint", type("string"));
        schema.set("properties", propertiesNode);
        schema.set("required", objectMapper.valueToTree(List.of(
                "category", "question", "reason", "responseType")));
        return new PublishedToolDefinition(
                "runtime-request-clarification", BuiltInToolExecutor.REQUEST_CLARIFICATION,
                "Pause execution and request required information from an authorized human",
                ToolType.BUILT_IN, 1, schema, objectMapper.createObjectNode(), objectMapper.createObjectNode());
    }

    private ObjectNode type(String type) {
        return objectMapper.createObjectNode().put("type", type);
    }

    private boolean requiresApproval(PublishedToolDefinition tool) {
        return tool.executionPolicy().path("approval").path("required").asBoolean(false);
    }

    private JsonNode approvalPolicy(PublishedToolDefinition tool) {
        JsonNode approval = tool.executionPolicy().path("approval");
        ObjectNode effective = approval.isObject()
                ? ((ObjectNode) approval).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        if (tool.executionPolicy().hasNonNull("operation")) {
            effective.set("operation", tool.executionPolicy().get("operation"));
        }
        if (tool.executionPolicy().hasNonNull("riskLevel")) {
            effective.set("riskLevel", tool.executionPolicy().get("riskLevel"));
        }
        return effective;
    }

    private HumanInteractionRequestSpec approvalRequest(PublishedToolDefinition tool, ToolCall call) {
        JsonNode policy = approvalPolicy(tool);
        JsonNode audienceNode = policy.path("audience");
        HumanAudienceType audience = switch (audienceNode.path("type").asText("RUN_REQUESTER")) {
            case "ROLE" -> HumanAudienceType.ROLE;
            case "GROUP" -> HumanAudienceType.GROUP;
            default -> HumanAudienceType.RUN_REQUESTER;
        };
        List<String> values = new ArrayList<>();
        audienceNode.path("values").forEach(value -> values.add(value.asText()));
        ObjectNode request = objectMapper.createObjectNode()
                .put("toolId", tool.id()).put("toolVersion", tool.version())
                .put("toolName", tool.name()).put("toolType", tool.type().name())
                .set("arguments", call.arguments());
        return new HumanInteractionRequestSpec(
                HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION",
                "Approve execution of tool " + tool.name(),
                "The published tool definition requires human approval before execution.",
                HumanResponseType.APPROVAL, request, audience, values,
                Duration.ofSeconds(policy.path("expiresAfterSeconds").asLong(86_400)));
    }

    private HumanInteractionRequestSpec effectiveClarificationPolicy(
            PublishedAgentDefinition agent, HumanInteractionRequestSpec requested) {
        JsonNode clarification = agent.humanInteractionPolicy() == null
                ? JsonNodeFactory.instance.objectNode()
                : agent.humanInteractionPolicy().path("clarification");
        JsonNode configuredAudience = clarification.path("defaultAudience");
        HumanAudienceType audience = switch (configuredAudience.path("type").asText("RUN_REQUESTER")) {
            case "ROLE" -> HumanAudienceType.ROLE;
            case "GROUP" -> HumanAudienceType.GROUP;
            default -> HumanAudienceType.RUN_REQUESTER;
        };
        List<String> values = new ArrayList<>();
        for (JsonNode value : configuredAudience.path("values")) {
            values.add(value.asText());
        }
        if (requested.audienceHint() == HumanAudienceType.CALLER_AGENT
                && "CALLER_CONTEXT".equals(requested.category())) {
            audience = HumanAudienceType.CALLER_AGENT;
            values = List.of();
        }
        return new HumanInteractionRequestSpec(
                requested.type(), requested.category(), requested.question(), requested.reason(),
                requested.responseType(), requested.request(), audience, values,
                Duration.ofSeconds(clarification.path("expiresAfterSeconds").asLong(86_400)),
                clarification.path("maxRequestsPerRootRun").asInt(20));
    }

    private PublishedToolDefinition resolveTool(String licenseCode, String name) {
        return toolDefinitionRepository.resolve(licenseCode, Set.of(name)).getFirst();
    }

    private void verifyBinding(AgentToolInvocationEntity invocation, PublishedToolDefinition tool) {
        JsonNode arguments = parseNullable(invocation.getArgumentsJson());
        if (tool.version() != invocation.getToolVersion()
                || !binding(tool, arguments).equals(invocation.getArgumentsSha256())) {
            throw new AgentExecutionException("Approved tool invocation binding changed");
        }
    }

    private String binding(PublishedToolDefinition tool, JsonNode arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String subject = tool.id() + ":" + tool.version() + ":" + writeJson(arguments);
            return HexFormat.of().formatHex(digest.digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, PublishedToolDefinition> index(List<PublishedToolDefinition> tools) {
        Map<String, PublishedToolDefinition> result = new LinkedHashMap<>();
        tools.forEach(tool -> result.put(tool.name(), tool));
        return result;
    }

    private void saveCheckpoint(
            String runId, int nextTurn, List<AgentMessage> messages, PendingWork pending,
            TokenUsage usage, ExecutionScope scope) {
        logStore.saveCheckpoint(runId, nextTurn, messages, pending, usage, scope, clock.instant());
    }

    private AgentRunResponse waitingResponse(AgentRunEntity localRun) {
        AgentRunEntity current = logStore.getRun(localRun.getId());
        return response(current, null, TokenUsage.ZERO);
    }

    private AgentRunResponse response(AgentRunEntity run, JsonNode output, TokenUsage usage) {
        List<PendingInteractionSummary> pending = humanInteractions == null
                ? List.of() : humanInteractions.pendingForRoot(run.getRootRunId());
        AgentRunStatus visibleStatus = pending.isEmpty() ? run.getStatus() : AgentRunStatus.WAITING_FOR_HUMAN;
        return new AgentRunResponse(
                run.getId(), run.getRootRunId(), run.getAgentId(), run.getAgentVersion(), visibleStatus,
                run.getProvider(), run.getModel(), output, usage, pending,
                run.getStartedAt(), run.getLastActivityAt(), run.getCompletedAt());
    }

    private JsonNode runInput(String task, JsonNode input) {
        return objectMapper.createObjectNode().put("task", task).set("input", input);
    }

    private JsonNode output(String text) {
        try {
            JsonNode json = objectMapper.readTree(text);
            return json == null ? objectMapper.getNodeFactory().textNode("") : json;
        } catch (JsonProcessingException exception) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    private JsonNode parseNullable(String json) {
        if (json == null) return null;
        return read(json, JsonNode.class);
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new AgentExecutionException("Unable to serialize agent execution state", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new AgentExecutionException("Invalid checkpoint", exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new AgentExecutionException("Invalid checkpoint", exception); }
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, clock.instant()).toMillis());
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record ExecutionScope(
            String rootRunId, String parentRunId, Long parentToolInvocationId, int depth,
            List<String> ancestry, int remainingInvocations, String requestedBy) {
        private ExecutionScope {
            ancestry = ancestry == null ? List.of() : List.copyOf(ancestry);
        }
    }

    private record PendingWork(
            String kind, Long invocationId, int turnNumber, List<ToolCall> calls,
            int callIndex, String interactionId, String childRunId) {
        private PendingWork {
            calls = calls == null ? List.of() : List.copyOf(calls);
        }

        private PendingWork withPending(
                Long invocationId, int callIndex, String interactionId, String childRunId) {
            return new PendingWork(kind, invocationId, turnNumber, calls, callIndex, interactionId, childRunId);
        }
    }

    private record ToolProgress(JsonNode output, String interactionId, String childRunId) {
        private static ToolProgress completed(JsonNode output) { return new ToolProgress(output, null, null); }
        private static ToolProgress waitingForHuman(String id) { return new ToolProgress(null, id, null); }
        private static ToolProgress waitingForChild(String id) { return new ToolProgress(null, null, id); }
        private boolean waiting() { return interactionId != null || childRunId != null; }
    }

    private static final class TokenCounter {
        private int input;
        private int output;
        private int total;

        private TokenCounter(TokenUsage initial) {
            input = initial.inputTokens(); output = initial.outputTokens(); total = initial.totalTokens();
        }
        private void add(TokenUsage usage) {
            input += usage.inputTokens(); output += usage.outputTokens(); total += usage.totalTokens();
        }
        private TokenUsage value() { return new TokenUsage(input, output, Math.max(total, input + output)); }
    }
}
