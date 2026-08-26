package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.definition.ManagementToolDefinitionRepository;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.ToolInvocationStatus;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.RunAgentRequest;
import com.manish.customagents.runtime.config.AgentExecutionProperties;
import com.manish.customagents.runtime.definition.ManagementAgentDefinitionRepository;
import com.manish.customagents.runtime.definition.PublishedAgentDefinition;
import com.manish.customagents.runtime.definition.StoredAgentDefinition;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.MessageRole;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.model.ToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutionContext;
import com.manish.customagents.runtime.tool.ToolExecutionRequest;
import com.manish.customagents.runtime.tool.ToolExecutionResult;
import com.manish.customagents.runtime.tool.CustomAgentToolExecutor;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutor;
import com.manish.customagents.runtime.tool.ToolExecutorRegistry;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.entity.HumanInteractionRequestEntity;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.model.PendingInteractionSummary;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

class AgentExecutionServiceTest {

    private static final String LICENSE_CODE = "tenant-1";
    private static final String AGENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHILD_AGENT_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManagementAgentDefinitionRepository agentRepository =
            mock(ManagementAgentDefinitionRepository.class);
    private final ManagementToolDefinitionRepository toolDefinitionRepository =
            mock(ManagementToolDefinitionRepository.class);
    private final BaseAgent genericAgent = mock(BaseAgent.class);
    private final AgentRunLogService logStore = Mockito.mock(AgentRunLogService.class);
    private final HumanInteractionService humanInteractions = mock(HumanInteractionService.class);
    private final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    private final AgentToolInvocationRepository invocationRepository = mock(AgentToolInvocationRepository.class);
    private final AgentExecutionProperties properties = new AgentExecutionProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void configureExecution() {
        properties.setProvider(ModelProvider.GOOGLE_GEMINI);
        properties.setModel("gemini-default");
        properties.setMaxTurns(4);
    }

    @Test
    void loadsPublishedAgentAndReturnsFinalJsonResponse() {
        when(agentRepository.get(LICENSE_CODE, AGENT_ID)).thenReturn(storedAgent(Set.of()));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of())).thenReturn(List.of());
        when(genericAgent.generate(any())).thenReturn(response(
                "{\"answer\":\"done\"}", List.of(), FinishReason.STOP, new TokenUsage(12, 5, 17)));
        AgentExecutionService service = service(List.of());

        AgentRunResponse result = service.run(
                LICENSE_CODE,
                new RunAgentRequest(AGENT_ID, "Create the strategy", input(), null, null));

        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(result.agentId()).isEqualTo(AGENT_ID);
        assertThat(result.agentVersion()).isEqualTo(3);
        assertThat(result.output().path("answer").asText()).isEqualTo("done");
        assertThat(result.usage().totalTokens()).isEqualTo(17);
        assertThat(result.provider()).isEqualTo("GOOGLE_GEMINI");
        assertThat(result.model()).isEqualTo("gemini-default");
        ArgumentCaptor<BaseAgentRequest> requestCaptor = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(genericAgent).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().model().provider()).isEqualTo(ModelProvider.GOOGLE_GEMINI);
        assertThat(requestCaptor.getValue().model().model()).isEqualTo("gemini-default");
        verify(agentRepository, org.mockito.Mockito.times(2)).get(LICENSE_CODE, AGENT_ID);
        verify(logStore).succeed(eq(result.runId()), eq(result.output()), eq(clock.instant()));
    }

    @Test
    void executesAllowedToolAndFeedsItsResultBackToTheGenericAgent() {
        PublishedToolDefinition campaignDefinition = campaignDefinition();
        ToolExecutor campaignExecutor = campaignExecutor();
        when(agentRepository.get(LICENSE_CODE, AGENT_ID))
                .thenReturn(storedAgent(Set.of("campaign.get")));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of("campaign.get")))
                .thenReturn(List.of(campaignDefinition));
        ToolCall toolCall = new ToolCall(
                "call-1", "campaign.get", objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        when(genericAgent.generate(any()))
                .thenReturn(response("", List.of(toolCall), FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12)))
                .thenReturn(response("final answer", List.of(), FinishReason.STOP, new TokenUsage(15, 4, 19)));
        AgentExecutionService service = service(List.of(campaignExecutor));

        AgentRunResponse result = service.run(
                LICENSE_CODE,
                new RunAgentRequest(AGENT_ID, "Inspect the campaign", input(), null, null));

        ArgumentCaptor<BaseAgentRequest> requestCaptor = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(genericAgent, org.mockito.Mockito.times(2)).generate(requestCaptor.capture());
        BaseAgentRequest secondRequest = requestCaptor.getAllValues().get(1);
        assertThat(secondRequest.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(MessageRole.TOOL);
            assertThat(message.toolCallId()).isEqualTo("call-1");
            assertThat(message.content()).contains("ACTIVE");
        });
        assertThat(secondRequest.tools()).extracting(ToolDefinition::name)
                .contains("campaign.get", "request_clarification");
        assertThat(result.output().asText()).isEqualTo("final answer");
        assertThat(result.usage().totalTokens()).isEqualTo(31);
        verify(logStore).beginTool(
                eq(result.runId()), eq(1), eq("call-1"), eq(campaignDefinition),
                any(), any(), any(), any());
        verify(logStore).completeTool(eq(null), any(), eq(0L), any());
    }

    @Test
    void usesProviderAndModelSelectedForTheRun() {
        when(agentRepository.get(LICENSE_CODE, AGENT_ID)).thenReturn(storedAgent(Set.of()));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of())).thenReturn(List.of());
        when(genericAgent.generate(any())).thenReturn(response(
                "selected model answer", List.of(), FinishReason.STOP, new TokenUsage(8, 3, 11)));
        AgentExecutionService service = service(List.of());

        AgentRunResponse result = service.run(
                LICENSE_CODE,
                new RunAgentRequest(
                        AGENT_ID, "Create the strategy", input(), ModelProvider.OPENAI, "gpt-customer"));

        ArgumentCaptor<BaseAgentRequest> requestCaptor = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(genericAgent).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().model().provider()).isEqualTo(ModelProvider.OPENAI);
        assertThat(requestCaptor.getValue().model().model()).isEqualTo("gpt-customer");
        assertThat(result.provider()).isEqualTo("OPENAI");
        assertThat(result.model()).isEqualTo("gpt-customer");
        verify(logStore).start(
                eq(result.runId()), eq(result.runId()), eq(null), eq(null),
                eq(LICENSE_CODE), eq("local-requester"), eq(AGENT_ID), eq(3),
                eq("OPENAI"), eq("gpt-customer"), any(), eq(clock.instant()));
    }

    @Test
    void routesCustomAgentThroughRegistryAndFeedsChildOutputBackToParent() {
        PublishedToolDefinition childTool = childAgentTool();
        when(agentRepository.get(LICENSE_CODE, AGENT_ID))
                .thenReturn(storedAgent(AGENT_ID, 3, Set.of("child.research")));
        when(agentRepository.get(LICENSE_CODE, CHILD_AGENT_ID))
                .thenReturn(storedAgent(CHILD_AGENT_ID, 2, Set.of()));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of("child.research")))
                .thenReturn(List.of(childTool));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of())).thenReturn(List.of());
        ToolCall childCall = new ToolCall(
                "call-child",
                "child.research",
                objectMapper.createObjectNode()
                        .put("task", "Research the campaign audience")
                        .set("input", objectMapper.createObjectNode().put("campaignId", "cmp-1")));
        when(genericAgent.generate(any()))
                .thenReturn(response("", List.of(childCall), FinishReason.TOOL_CALLS,
                        new TokenUsage(10, 2, 12)))
                .thenReturn(response("child analysis", List.of(), FinishReason.STOP,
                        new TokenUsage(7, 3, 10)))
                .thenReturn(response("parent final", List.of(), FinishReason.STOP,
                        new TokenUsage(15, 4, 19)));
        AgentExecutionService service = service(List.of(new CustomAgentToolExecutor()));

        AgentRunResponse result = service.run(
                LICENSE_CODE,
                new RunAgentRequest(AGENT_ID, "Build the strategy", input(), null, null));

        ArgumentCaptor<BaseAgentRequest> requestCaptor = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(genericAgent, org.mockito.Mockito.times(3)).generate(requestCaptor.capture());
        BaseAgentRequest childRequest = requestCaptor.getAllValues().get(1);
        assertThat(childRequest.model().provider()).isEqualTo(ModelProvider.GOOGLE_GEMINI);
        assertThat(childRequest.model().model()).isEqualTo("gemini-default");
        assertThat(childRequest.messages()).anySatisfy(message ->
                assertThat(message.content()).contains("Research the campaign audience"));
        BaseAgentRequest finalParentRequest = requestCaptor.getAllValues().get(2);
        assertThat(finalParentRequest.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(MessageRole.TOOL);
            assertThat(message.content()).contains("child analysis");
        });
        assertThat(result.output().asText()).isEqualTo("parent final");
        verify(agentRepository, org.mockito.Mockito.times(2)).get(LICENSE_CODE, CHILD_AGENT_ID);
    }

    @Test
    void pausesBeforeAProtectedToolAndDoesNotDispatchIt() {
        PublishedToolDefinition protectedTool = new PublishedToolDefinition(
                "tool-protected", "campaign.send", "Sends a campaign", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("method", "POST").put("url", "https://api.example/send"),
                objectMapper.createObjectNode().set("approval",
                        objectMapper.createObjectNode().put("required", true)));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.type()).thenReturn(ToolType.HTTP);
        when(agentRepository.get(LICENSE_CODE, AGENT_ID))
                .thenReturn(storedAgent(Set.of("campaign.send")));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of("campaign.send")))
                .thenReturn(List.of(protectedTool));
        ToolCall call = new ToolCall("protected-call", "campaign.send",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        when(genericAgent.generate(any())).thenReturn(response(
                "", List.of(call), FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12)));
        AgentExecutionService service = service(List.of(executor));
        HumanInteractionRequestEntity interaction = interaction(
                "interaction-approval", HumanInteractionType.TOOL_APPROVAL, HumanResponseType.APPROVAL);
        when(humanInteractions.create(any(), any(), any(), any())).thenReturn(interaction);
        when(humanInteractions.pendingForRoot(any())).thenReturn(List.of(summary(interaction)));

        AgentRunResponse result = service.run(
                LICENSE_CODE, new RunAgentRequest(AGENT_ID, "Send campaign", input(), null, null));

        assertThat(result.status()).isEqualTo(AgentRunStatus.WAITING_FOR_HUMAN);
        assertThat(result.pendingInteractions()).hasSize(1);
        verify(executor, Mockito.never()).execute(any());
        verify(logStore).waitForApproval(any(), any(), any());
        verify(logStore).saveCheckpoint(any(), eq(2), any(), any(), any(), any(), any());
    }

    @Test
    void pausesWhenTheBuiltInClarificationToolIsCalled() {
        when(agentRepository.get(LICENSE_CODE, AGENT_ID)).thenReturn(storedAgent(Set.of()));
        when(toolDefinitionRepository.resolve(LICENSE_CODE, Set.of())).thenReturn(List.of());
        ToolCall call = new ToolCall("clarify-call", "request_clarification",
                objectMapper.createObjectNode()
                        .put("category", "MISSING_TASK_INPUT")
                        .put("question", "Which segment?")
                        .put("reason", "The task is ambiguous")
                        .put("responseType", "FREE_TEXT"));
        when(genericAgent.generate(any())).thenReturn(response(
                "", List.of(call), FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12)));
        AgentExecutionService service = service(List.of(new com.manish.customagents.runtime.tool.BuiltInToolExecutor()));
        HumanInteractionRequestEntity interaction = interaction(
                "interaction-clarification", HumanInteractionType.CLARIFICATION, HumanResponseType.FREE_TEXT);
        when(humanInteractions.create(any(), any(), any(), any())).thenReturn(interaction);
        when(humanInteractions.pendingForRoot(any())).thenReturn(List.of(summary(interaction)));

        AgentRunResponse result = service.run(
                LICENSE_CODE, new RunAgentRequest(AGENT_ID, "Analyze audience", input(), null, null));

        assertThat(result.status()).isEqualTo(AgentRunStatus.WAITING_FOR_HUMAN);
        assertThat(result.pendingInteractions().getFirst().type())
                .isEqualTo(HumanInteractionType.CLARIFICATION);
        verify(logStore).waitForClarification(any(), any(), any());
    }

    @Test
    void ordersBatchResumeTargetsFromDeepestChildToRoot() {
        AgentExecutionService service = service(List.of());
        AgentRunEntity root = persistedRun("root-run", "root-run", null);
        AgentRunEntity child = persistedRun("child-run", "root-run", "root-run");
        AgentRunEntity grandchild = persistedRun("grandchild-run", "root-run", "child-run");
        Map<String, AgentRunEntity> runs = Map.of(
                root.getId(), root, child.getId(), child, grandchild.getId(), grandchild);

        List<HumanInteractionService.ResumeTarget> ordered = service.deepestFirst(List.of(
                new HumanInteractionService.ResumeTarget("root-interaction", root.getId()),
                new HumanInteractionService.ResumeTarget("child-interaction", child.getId()),
                new HumanInteractionService.ResumeTarget("grandchild-interaction", grandchild.getId())), runs);

        assertThat(ordered).extracting(HumanInteractionService.ResumeTarget::runId)
                .containsExactly("grandchild-run", "child-run", "root-run");
    }

    private AgentExecutionService service(List<ToolExecutor> executors) {
        Map<String, AgentRunEntity> runs = new HashMap<>();
        Mockito.doAnswer(invocation -> {
            AgentRunEntity run = new AgentRunEntity(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6), invocation.getArgument(7), invocation.getArgument(8),
                    invocation.getArgument(9), objectMapper.writeValueAsString(invocation.getArgument(10)),
                    invocation.getArgument(11));
            runs.put(run.getId(), run);
            return null;
        }).when(logStore).start(any(), any(), any(), any(), any(), any(), any(),
                Mockito.anyInt(), any(), any(), any(), any());
        when(logStore.getRun(any())).thenAnswer(invocation -> runs.get(invocation.getArgument(0)));
        Mockito.doAnswer(invocation -> {
            runs.get(invocation.getArgument(0)).succeed(
                    objectMapper.writeValueAsString(invocation.getArgument(1)), invocation.getArgument(2));
            return null;
        }).when(logStore).succeed(any(), any(), any());
        when(logStore.beginTool(any(), Mockito.anyInt(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new AgentToolInvocationEntity(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                        ((PublishedToolDefinition) invocation.getArgument(3)).name(),
                        ((PublishedToolDefinition) invocation.getArgument(3)).id(),
                        ((PublishedToolDefinition) invocation.getArgument(3)).version(),
                        ((PublishedToolDefinition) invocation.getArgument(3)).type().name(),
                        objectMapper.writeValueAsString(invocation.getArgument(4)),
                        invocation.getArgument(5), objectMapper.writeValueAsString(invocation.getArgument(6)),
                        invocation.getArgument(7)));
        when(humanInteractions.pendingForRoot(any())).thenReturn(List.of());
        return new AgentExecutionService(
                agentRepository,
                toolDefinitionRepository,
                new ToolExecutorRegistry(executors),
                genericAgent,
                new AgentPromptBuilder(objectMapper),
                logStore,
                humanInteractions,
                runRepository,
                invocationRepository,
                properties,
                objectMapper,
                clock);
    }

    private AgentRunEntity persistedRun(String id, String rootId, String parentId) {
        return new AgentRunEntity(
                id, rootId, parentId, null, LICENSE_CODE, "requester", AGENT_ID, 1,
                "GOOGLE_GEMINI", "gemini-default", "{}", clock.instant());
    }

    private StoredAgentDefinition storedAgent(Set<String> tools) {
        return storedAgent(AGENT_ID, 3, tools);
    }

    private StoredAgentDefinition storedAgent(String id, int version, Set<String> tools) {
        PublishedAgentDefinition definition = new PublishedAgentDefinition(
                "Strategy agent",
                "Creates a strategy",
                "Marketing expert",
                "Create a strategy from the supplied data.",
                List.of("Return concise output"),
                "JSON strategy",
                null,
                null,
                List.of(),
                tools,
                null);
        return new StoredAgentDefinition(
                id, LICENSE_CODE, "PUBLISHED", "ACTIVE", version, definition);
    }

    private ToolExecutor campaignExecutor() {
        return new ToolExecutor() {
            @Override
            public ToolType type() {
                return ToolType.HTTP;
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                return ToolExecutionResult.of(objectMapper.createObjectNode()
                        .put("campaignId", request.arguments().path("campaignId").asText())
                        .put("status", "ACTIVE"));
            }
        };
    }

    private PublishedToolDefinition campaignDefinition() {
        return new PublishedToolDefinition(
                "tool-1",
                "campaign.get",
                "Gets a campaign",
                ToolType.HTTP,
                2,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode()
                        .put("method", "GET")
                        .put("url", "https://api.example/campaigns/{campaignId}"),
                objectMapper.createObjectNode());
    }

    private PublishedToolDefinition childAgentTool() {
        return new PublishedToolDefinition(
                "tool-child",
                "child.research",
                "Delegates research to a child agent",
                ToolType.CUSTOM_AGENT,
                1,
                objectMapper.createObjectNode()
                        .put("type", "object")
                        .set("properties", objectMapper.createObjectNode()
                                .set("task", objectMapper.createObjectNode().put("type", "string"))),
                objectMapper.createObjectNode()
                        .put("agentId", CHILD_AGENT_ID)
                        .put("agentVersion", 2)
                        .set("settings", objectMapper.createObjectNode()),
                objectMapper.createObjectNode());
    }

    private JsonNode input() {
        return objectMapper.createObjectNode().put("campaignId", "cmp-1");
    }

    private HumanInteractionRequestEntity interaction(
            String id, HumanInteractionType type, HumanResponseType responseType) {
        return new HumanInteractionRequestEntity(
                id, LICENSE_CODE, "root-run", "run-id", null, type, "category",
                "question", "reason", responseType, "{}", HumanAudienceType.RUN_REQUESTER,
                "[]", "local-requester", "binding", clock.instant().plus(Duration.ofHours(24)),
                clock.instant());
    }

    private PendingInteractionSummary summary(HumanInteractionRequestEntity interaction) {
        return new PendingInteractionSummary(
                interaction.getId(), interaction.getType(), HumanInteractionStatus.PENDING,
                interaction.getRunId(), interaction.getQuestion(), interaction.getResponseType(),
                interaction.getAudienceType(), List.of(), interaction.getAssignedUserId(),
                interaction.getExpiresAt());
    }

    private BaseAgentResponse response(
            String text, List<ToolCall> calls, FinishReason finishReason, TokenUsage usage) {
        return new BaseAgentResponse("response-1", text, calls, finishReason, usage, Map.of());
    }
}
