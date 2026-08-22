package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.config.AgentExecutionProperties;
import com.manish.customagents.runtime.definition.DraftAgentDefinition;
import com.manish.customagents.runtime.definition.ManagementAgentDefinitionRepository;
import com.manish.customagents.runtime.definition.ManagementToolDefinitionRepository;
import com.manish.customagents.runtime.definition.StoredAgentDefinition;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import com.manish.customagents.runtime.errors.DraftRevisionConflictException;
import com.manish.customagents.runtime.errors.InvalidTestInteractionException;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.DraftAgentTestRequest;
import com.manish.customagents.runtime.model.DraftAgentTestResponse;
import com.manish.customagents.runtime.model.DraftAgentTestStatus;
import com.manish.customagents.runtime.model.DraftTestConversationEntry;
import com.manish.customagents.runtime.model.DraftTestPendingInteraction;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.GenerationOptions;
import com.manish.customagents.runtime.model.MockedToolCall;
import com.manish.customagents.runtime.model.ModelSelection;
import com.manish.customagents.runtime.model.TestHumanResponse;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.contracts.JsonDigest;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.tool.BuiltInToolExecutor;
import com.manish.customagents.runtime.tool.HumanInteractionRequestSpec;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutionContext;
import com.manish.customagents.runtime.tool.ToolExecutionRequest;
import com.manish.customagents.runtime.tool.ToolExecutionResult;
import com.manish.customagents.runtime.tool.ToolExecutorRegistry;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.runtime.tool.UnsupportedToolTypeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DraftAgentTestService {

    private final ManagementAgentDefinitionRepository agentRepository;
    private final ManagementToolDefinitionRepository toolRepository;
    private final ToolExecutorRegistry toolExecutorRegistry;
    private final BaseAgent baseAgent;
    private final AgentPromptBuilder promptBuilder;
    private final DraftTestInteractionTokenService tokenService;
    private final AgentExecutionProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DraftAgentTestService(
            ManagementAgentDefinitionRepository agentRepository,
            ManagementToolDefinitionRepository toolRepository,
            ToolExecutorRegistry toolExecutorRegistry,
            BaseAgent baseAgent,
            AgentPromptBuilder promptBuilder,
            DraftTestInteractionTokenService tokenService,
            AgentExecutionProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.agentRepository = agentRepository;
        this.toolRepository = toolRepository;
        this.toolExecutorRegistry = toolExecutorRegistry;
        this.baseAgent = baseAgent;
        this.promptBuilder = promptBuilder;
        this.tokenService = tokenService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public DraftAgentTestResponse test(
            String licenseCode,
            DraftAgentTestRequest command,
            String requestedBy) {
        DraftAgentDefinition draft = agentRepository.getDraftForTest(licenseCode, command.agentId());
        if (command.expectedDraftRevision() != null
                && !command.expectedDraftRevision().equals(draft.revision())) {
            throw new DraftRevisionConflictException(command.expectedDraftRevision(), draft.revision());
        }
        ModelSelection model = command.modelSelectionOr(properties.defaultModelSelection());
        String requestFingerprint = requestFingerprint(command, model);
        Map<String, ResolvedHumanResponse> responses = resolveHumanResponses(
                command.humanResponses(), licenseCode, command.agentId(), draft.revision(), requestFingerprint);
        String testRunId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        TestSession session = new TestSession(
                testRunId, licenseCode, command.agentId(), draft.revision(), requestFingerprint,
                requestedBy, model, command.mockToolResults(), responses);
        TestOutcome outcome = executeAgent(
                draft.agent(), command.task(), command.input(), 0, List.of(), session, true);
        Instant completedAt = clock.instant();
        return new DraftAgentTestResponse(
                testRunId, command.agentId(), draft.revision(),
                outcome.pending() == null ? DraftAgentTestStatus.COMPLETED : DraftAgentTestStatus.NEEDS_INPUT,
                model.provider().name(), model.model(), outcome.output(), session.usage(),
                outcome.pending() == null ? List.of() : List.of(outcome.pending()),
                session.mockedToolCalls(), session.conversation(), startedAt, completedAt);
    }

    private TestOutcome executeAgent(
            StoredAgentDefinition agent,
            String task,
            JsonNode input,
            int depth,
            List<String> parentAncestry,
            TestSession session,
            boolean root) {
        if (parentAncestry.contains(agent.id())) {
            throw new AgentExecutionException("Custom-agent invocation cycle detected: "
                    + String.join(" -> ", parentAncestry) + " -> " + agent.id());
        }
        if (depth > properties.getMaxAgentDepth()) {
            throw new AgentExecutionException(
                    "Custom-agent invocation exceeded maximum depth " + properties.getMaxAgentDepth());
        }
        if (!root && ++session.agentInvocations > properties.getMaxAgentInvocations()) {
            throw new AgentExecutionException("Custom-agent invocation exceeded invocation budget");
        }
        List<String> ancestry = new ArrayList<>(parentAncestry);
        ancestry.add(agent.id());
        List<PublishedToolDefinition> allowedTools = allowedTools(session.licenseCode, agent);
        preflightTools(session.licenseCode, allowedTools);
        Map<String, PublishedToolDefinition> toolsByName = index(allowedTools);
        List<AgentMessage> messages = new ArrayList<>(promptBuilder.build(agent.definition(), task, input));
        appendHumanContext(messages, session.responses);
        messages.forEach(message -> session.conversation.add(
                new DraftTestConversationEntry(agent.id(), depth, 0, message)));
        String localRunId = root ? session.testRunId : UUID.randomUUID().toString();

        for (int turn = 1; turn <= properties.getMaxTurns(); turn++) {
            BaseAgentRequest request = new BaseAgentRequest(
                    session.model, messages,
                    allowedTools.stream().map(PublishedToolDefinition::toLlmDefinition).toList(),
                    new GenerationOptions(null, null, true, agent.definition().outputSchema()),
                    Map.of(
                            "testMode", "true",
                            "testRunId", session.testRunId,
                            "runId", localRunId,
                            "licenseCode", session.licenseCode,
                            "agentId", agent.id(),
                            "agentVersion", Integer.toString(agent.version()),
                            "invocationDepth", Integer.toString(depth),
                            "requestedBy", session.requestedBy));
            BaseAgentResponse llmResponse = baseAgent.generate(request);
            session.addUsage(llmResponse.usage());
            AgentMessage assistant = llmResponse.toAssistantMessage();
            messages.add(assistant);
            session.conversation.add(new DraftTestConversationEntry(agent.id(), depth, turn, assistant));

            if (!llmResponse.toolCalls().isEmpty()) {
                for (ToolCall call : llmResponse.toolCalls()) {
                    PublishedToolDefinition tool = toolsByName.get(call.name());
                    if (tool == null) {
                        throw new AgentExecutionException("Tool call is not allowed: " + call.name());
                    }
                    TestOutcome toolOutcome = executeTool(
                            agent, tool, call, depth, ancestry, localRunId, session);
                    if (toolOutcome.pending() != null) return toolOutcome;
                    AgentMessage toolMessage = AgentMessage.toolResult(
                            call.id(), call.name(), writeJson(toolOutcome.output()));
                    messages.add(toolMessage);
                    session.conversation.add(new DraftTestConversationEntry(
                            agent.id(), depth, turn, toolMessage));
                }
                continue;
            }
            if (llmResponse.finishReason() == FinishReason.STOP) {
                return TestOutcome.completed(output(llmResponse.text()));
            }
            throw new AgentExecutionException(
                    "Generic agent stopped without a final response: " + llmResponse.finishReason());
        }
        throw new AgentExecutionException(
                "Custom agent exceeded the maximum of " + properties.getMaxTurns() + " turns");
    }

    private TestOutcome executeTool(
            StoredAgentDefinition agent,
            PublishedToolDefinition tool,
            ToolCall call,
            int depth,
            List<String> ancestry,
            String localRunId,
            TestSession session) {
        String binding = binding(tool, call.arguments());
        if (requiresApproval(tool)) {
            String interactionKey = JsonDigest.sha256("APPROVAL:" + binding);
            ResolvedHumanResponse response = session.responses.get(interactionKey);
            if (response == null) {
                return TestOutcome.pending(pendingApproval(session, tool, call, binding, interactionKey));
            }
            if (response.action() == HumanResponseAction.REJECT) {
                ObjectNode rejected = objectMapper.createObjectNode()
                        .put("status", "REJECTED")
                        .put("toolName", tool.name())
                        .put("message", "The test tool call was rejected by the human reviewer.");
                return TestOutcome.completed(rejected);
            }
        }

        try {
            ToolExecutionContext context = ToolExecutionContext.draftTest(
                    localRunId, session.licenseCode, agent.id(), agent.version(),
                    child -> {
                        StoredAgentDefinition childAgent = agentRepository.get(
                                session.licenseCode, child.childAgentId());
                        if (childAgent.version() != child.childAgentVersion()) {
                            throw new AgentExecutionException("Published custom agent "
                                    + child.childAgentId() + " is version " + childAgent.version()
                                    + " but tool requires version " + child.childAgentVersion());
                        }
                        TestOutcome childOutcome = executeAgent(
                                childAgent, child.task(), child.input(), depth + 1, ancestry, session, false);
                        if (childOutcome.pending() != null) {
                            session.propagatedChildInteraction = childOutcome.pending();
                            return ToolExecutionResult.waiting(pendingSpec(childOutcome.pending()));
                        }
                        ObjectNode result = objectMapper.createObjectNode()
                                .put("status", "SUCCEEDED")
                                .set("output", childOutcome.output());
                        return new ToolExecutionResult(result, Map.of(
                                "testMode", "true",
                                "childAgentId", childAgent.id(),
                                "childAgentVersion", Integer.toString(childAgent.version())));
                    },
                    session.mockToolResults);
            ToolExecutionResult result = toolExecutorRegistry.execute(
                    new ToolExecutionRequest(tool, context, call.arguments()));
            if (result.isWaitingForHuman()) {
                if (session.propagatedChildInteraction != null) {
                    DraftTestPendingInteraction childInteraction = session.propagatedChildInteraction;
                    session.propagatedChildInteraction = null;
                    return TestOutcome.pending(childInteraction);
                }
                HumanInteractionRequestSpec spec = result.humanInteraction();
                String interactionKey = clarificationKey(spec);
                ResolvedHumanResponse response = session.responses.get(interactionKey);
                if (response == null) {
                    return TestOutcome.pending(pendingClarification(session, spec, interactionKey));
                }
                ObjectNode answer = objectMapper.createObjectNode()
                        .put("status", "ANSWERED")
                        .put("interactionId", response.claims().interactionId())
                        .set("answer", response.answer());
                return TestOutcome.completed(answer);
            }
            if (result.isWaitingForChild()) {
                throw new AgentExecutionException(
                        "Draft test child execution unexpectedly requested durable waiting state");
            }
            if ("true".equals(result.metadata().get("mocked"))) {
                session.mockedToolCalls.add(new MockedToolCall(
                        tool.name(), result.metadata().get("operation"), call.arguments(), result.output()));
            }
            return TestOutcome.completed(result.output());
        } catch (UnsupportedToolTypeException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            ObjectNode error = objectMapper.createObjectNode()
                    .put("error", safeMessage(exception))
                    .put("tool", call.name());
            return TestOutcome.completed(error);
        }
    }

    private List<PublishedToolDefinition> allowedTools(
            String licenseCode,
            StoredAgentDefinition agent) {
        List<PublishedToolDefinition> tools = new ArrayList<>(
                toolRepository.resolve(licenseCode, agent.definition().allowedTools()));
        tools.add(clarificationTool());
        return List.copyOf(tools);
    }

    private void preflightTools(String licenseCode, List<PublishedToolDefinition> tools) {
        for (PublishedToolDefinition tool : tools) {
            if (tool.type() == ToolType.CUSTOM_AGENT) {
                String childAgentId = tool.configuration().path("agentId").asText("").strip();
                int expectedVersion = tool.configuration().path("agentVersion").asInt(0);
                if (childAgentId.isEmpty() || expectedVersion < 1) {
                    throw new AgentExecutionException(
                            "CUSTOM_AGENT tool " + tool.name() + " has an invalid target binding");
                }
                StoredAgentDefinition child = agentRepository.get(licenseCode, childAgentId);
                if (child.version() != expectedVersion) {
                    throw new AgentExecutionException("Published custom agent " + childAgentId
                            + " is version " + child.version() + " but tool requires version " + expectedVersion);
                }
                if (!toolExecutorRegistry.supports(ToolType.CUSTOM_AGENT)) {
                    throw new UnsupportedToolTypeException(ToolType.CUSTOM_AGENT);
                }
            } else if (tool.type() == ToolType.BUILT_IN) {
                if (!toolExecutorRegistry.supports(ToolType.BUILT_IN)) {
                    throw new UnsupportedToolTypeException(ToolType.BUILT_IN);
                }
            } else if ("READ".equals(operation(tool)) && !toolExecutorRegistry.supports(tool.type())) {
                throw new UnsupportedToolTypeException(tool.type());
            }
        }
    }

    private PublishedToolDefinition clarificationTool() {
        ObjectNode schema = objectMapper.createObjectNode().put("type", "object");
        ObjectNode fields = objectMapper.createObjectNode();
        fields.set("category", type("string"));
        fields.set("question", type("string"));
        fields.set("reason", type("string"));
        fields.set("responseType", type("string"));
        fields.set("audienceHint", type("string"));
        schema.set("properties", fields);
        schema.set("required", objectMapper.valueToTree(List.of(
                "category", "question", "reason", "responseType")));
        return new PublishedToolDefinition(
                "runtime-request-clarification", BuiltInToolExecutor.REQUEST_CLARIFICATION,
                "Pause execution and request required information from an authorized human",
                ToolType.BUILT_IN, 1, schema,
                objectMapper.createObjectNode(), objectMapper.createObjectNode());
    }

    private ObjectNode type(String name) {
        return objectMapper.createObjectNode().put("type", name);
    }

    private boolean requiresApproval(PublishedToolDefinition tool) {
        return tool.executionPolicy().path("approval").path("required").asBoolean(false);
    }

    private DraftTestPendingInteraction pendingApproval(
            TestSession session,
            PublishedToolDefinition tool,
            ToolCall call,
            String toolBinding,
            String interactionKey) {
        ObjectNode request = objectMapper.createObjectNode()
                .put("toolId", tool.id())
                .put("toolVersion", tool.version())
                .put("toolName", tool.name())
                .put("toolType", tool.type().name())
                .set("arguments", call.arguments());
        HumanInteractionRequestSpec spec = new HumanInteractionRequestSpec(
                HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION",
                "Approve execution of tool " + tool.name(),
                "The published tool definition requires human approval before execution.",
                HumanResponseType.APPROVAL, request, HumanAudienceType.RUN_REQUESTER,
                List.of(), Duration.ofMinutes(30));
        return pending(session, spec, interactionKey, toolBinding);
    }

    private DraftTestPendingInteraction pendingClarification(
            TestSession session,
            HumanInteractionRequestSpec spec,
            String interactionKey) {
        return pending(session, spec, interactionKey, null);
    }

    private DraftTestPendingInteraction pending(
            TestSession session,
            HumanInteractionRequestSpec spec,
            String interactionKey,
            String toolBinding) {
        String interactionId = UUID.randomUUID().toString();
        DraftTestInteractionTokenService.InteractionClaims claims =
                new DraftTestInteractionTokenService.InteractionClaims(
                        interactionId, interactionKey, spec.type(), spec.category(),
                        spec.question(), spec.reason(), spec.responseType(), spec.request(), toolBinding);
        String token = tokenService.issue(
                session.licenseCode, session.rootAgentId, session.draftRevision,
                session.requestFingerprint, claims);
        return new DraftTestPendingInteraction(
                interactionId, spec.type(), spec.category(), spec.question(), spec.reason(),
                spec.responseType(), spec.request(), token);
    }

    private HumanInteractionRequestSpec pendingSpec(DraftTestPendingInteraction pending) {
        return new HumanInteractionRequestSpec(
                pending.type(), pending.category(), pending.question(), pending.reason(),
                pending.responseType(), pending.request(), HumanAudienceType.RUN_REQUESTER,
                List.of(), Duration.ofMinutes(30));
    }

    private Map<String, ResolvedHumanResponse> resolveHumanResponses(
            List<TestHumanResponse> responses,
            String licenseCode,
            String rootAgentId,
            String draftRevision,
            String requestFingerprint) {
        Map<String, ResolvedHumanResponse> resolved = new LinkedHashMap<>();
        for (TestHumanResponse response : responses) {
            DraftTestInteractionTokenService.TokenPayload claims = tokenService.verify(
                    response.interactionToken(), licenseCode, rootAgentId,
                    draftRevision, requestFingerprint);
            validateAction(claims, response);
            ResolvedHumanResponse value = new ResolvedHumanResponse(
                    claims, response.action(), response.answer() == null ? null : response.answer().deepCopy());
            if (resolved.putIfAbsent(claims.interactionKey(), value) != null) {
                throw new InvalidTestInteractionException(
                        "Only one response may be submitted for a draft test interaction");
            }
        }
        return Map.copyOf(resolved);
    }

    private void validateAction(
            DraftTestInteractionTokenService.TokenPayload claims,
            TestHumanResponse response) {
        if (claims.type() == HumanInteractionType.CLARIFICATION) {
            if (response.action() != HumanResponseAction.ANSWER
                    || response.answer() == null || response.answer().isNull()) {
                throw new InvalidTestInteractionException(
                        "Clarification interactions require ANSWER with a non-null answer");
            }
            return;
        }
        if (claims.type() == HumanInteractionType.TOOL_APPROVAL
                && response.action() != HumanResponseAction.APPROVE
                && response.action() != HumanResponseAction.REJECT) {
            throw new InvalidTestInteractionException(
                    "Tool approval interactions require APPROVE or REJECT");
        }
    }

    private void appendHumanContext(
            List<AgentMessage> messages,
            Map<String, ResolvedHumanResponse> responses) {
        List<ResolvedHumanResponse> clarifications = responses.values().stream()
                .filter(response -> response.claims().type() == HumanInteractionType.CLARIFICATION)
                .sorted(Comparator.comparing(response -> response.claims().interactionId()))
                .toList();
        if (clarifications.isEmpty()) return;
        StringBuilder context = new StringBuilder("HUMAN CLARIFICATIONS ALREADY PROVIDED\n");
        for (ResolvedHumanResponse response : clarifications) {
            context.append("QUESTION\n").append(response.claims().question())
                    .append("\nANSWER\n").append(writeJson(response.answer())).append("\n\n");
        }
        context.append("Use these answers as authoritative context and do not ask the same questions again.");
        messages.add(AgentMessage.user(context.toString()));
    }

    private String requestFingerprint(DraftAgentTestRequest command, ModelSelection model) {
        ObjectNode fingerprint = objectMapper.createObjectNode()
                .put("agentId", command.agentId())
                .put("task", command.task() == null ? "" : command.task())
                .put("provider", model.provider().name())
                .put("model", model.model());
        fingerprint.set("input", canonical(command.input()));
        ObjectNode mocks = objectMapper.createObjectNode();
        new TreeMap<>(command.mockToolResults()).forEach((name, value) ->
                mocks.set(name, canonical(value)));
        fingerprint.set("mockToolResults", mocks);
        return JsonDigest.sha256(writeJson(fingerprint));
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
        sorted.forEach((name, child) -> result.set(name, canonical(child)));
        return result;
    }

    private String clarificationKey(HumanInteractionRequestSpec spec) {
        return JsonDigest.sha256("CLARIFICATION:" + spec.category() + ":" + spec.question()
                + ":" + writeJson(canonical(spec.request())));
    }

    private String binding(PublishedToolDefinition tool, JsonNode arguments) {
        return JsonDigest.sha256(tool.id() + ":" + tool.version() + ":" + writeJson(canonical(arguments)));
    }

    private String operation(PublishedToolDefinition tool) {
        return tool.executionPolicy().path("operation").asText("").strip().toUpperCase(Locale.ROOT);
    }

    private Map<String, PublishedToolDefinition> index(List<PublishedToolDefinition> tools) {
        Map<String, PublishedToolDefinition> indexed = new LinkedHashMap<>();
        tools.forEach(tool -> indexed.put(tool.name(), tool));
        return Map.copyOf(indexed);
    }

    private JsonNode output(String text) {
        try {
            JsonNode parsed = objectMapper.readTree(text);
            return parsed == null ? JsonNodeFactory.instance.textNode("") : parsed;
        } catch (JsonProcessingException exception) {
            return JsonNodeFactory.instance.textNode(text);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException("Unable to serialize draft test state", exception);
        }
    }


    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record TestOutcome(JsonNode output, DraftTestPendingInteraction pending) {
        static TestOutcome completed(JsonNode output) {
            return new TestOutcome(output, null);
        }

        static TestOutcome pending(DraftTestPendingInteraction interaction) {
            return new TestOutcome(null, interaction);
        }
    }

    private record ResolvedHumanResponse(
            DraftTestInteractionTokenService.TokenPayload claims,
            HumanResponseAction action,
            JsonNode answer) {
    }

    private static final class TestSession {
        private final String testRunId;
        private final String licenseCode;
        private final String rootAgentId;
        private final String draftRevision;
        private final String requestFingerprint;
        private final String requestedBy;
        private final ModelSelection model;
        private final Map<String, JsonNode> mockToolResults;
        private final Map<String, ResolvedHumanResponse> responses;
        private final List<MockedToolCall> mockedToolCalls = new ArrayList<>();
        private final List<DraftTestConversationEntry> conversation = new ArrayList<>();
        private int inputTokens;
        private int outputTokens;
        private int totalTokens;
        private int agentInvocations;
        private DraftTestPendingInteraction propagatedChildInteraction;

        private TestSession(
                String testRunId,
                String licenseCode,
                String rootAgentId,
                String draftRevision,
                String requestFingerprint,
                String requestedBy,
                ModelSelection model,
                Map<String, JsonNode> mockToolResults,
                Map<String, ResolvedHumanResponse> responses) {
            this.testRunId = testRunId;
            this.licenseCode = licenseCode;
            this.rootAgentId = rootAgentId;
            this.draftRevision = draftRevision;
            this.requestFingerprint = requestFingerprint;
            this.requestedBy = requestedBy;
            this.model = model;
            this.mockToolResults = mockToolResults;
            this.responses = responses;
        }

        private void addUsage(TokenUsage usage) {
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            totalTokens += usage.totalTokens();
        }

        private TokenUsage usage() {
            return new TokenUsage(inputTokens, outputTokens, totalTokens);
        }

        private List<MockedToolCall> mockedToolCalls() {
            return List.copyOf(mockedToolCalls);
        }

        private List<DraftTestConversationEntry> conversation() {
            return List.copyOf(conversation);
        }
    }
}
