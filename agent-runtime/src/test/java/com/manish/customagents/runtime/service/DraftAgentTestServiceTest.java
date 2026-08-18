package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.config.AgentExecutionProperties;
import com.manish.customagents.runtime.config.DraftAgentTestProperties;
import com.manish.customagents.runtime.definition.DraftAgentDefinition;
import com.manish.customagents.runtime.definition.ManagementAgentDefinitionRepository;
import com.manish.customagents.runtime.definition.ManagementToolDefinitionRepository;
import com.manish.customagents.runtime.definition.AgentNotPublishedException;
import com.manish.customagents.runtime.definition.PublishedAgentDefinition;
import com.manish.customagents.runtime.definition.StoredAgentDefinition;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.DraftAgentTestRequest;
import com.manish.customagents.runtime.model.DraftAgentTestResponse;
import com.manish.customagents.runtime.model.DraftAgentTestStatus;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.TestHumanResponse;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.tool.BuiltInToolExecutor;
import com.manish.customagents.runtime.tool.CustomAgentToolExecutor;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.ToolExecutionResult;
import com.manish.customagents.runtime.tool.ToolExecutor;
import com.manish.customagents.runtime.tool.ToolExecutorRegistry;
import com.manish.customagents.runtime.tool.ToolType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DraftAgentTestServiceTest {

    private static final String LICENSE = "tenant-1";
    private static final String AGENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ManagementAgentDefinitionRepository agentRepository =
            mock(ManagementAgentDefinitionRepository.class);
    private final ManagementToolDefinitionRepository toolRepository =
            mock(ManagementToolDefinitionRepository.class);
    private final BaseAgent baseAgent = mock(BaseAgent.class);
    private final AgentExecutionProperties executionProperties = new AgentExecutionProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-14T08:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        executionProperties.setProvider(ModelProvider.GOOGLE_GEMINI);
        executionProperties.setModel("gemini-default");
        executionProperties.setMaxTurns(4);
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(stored(AGENT_ID, "DRAFT", 1, Set.of()), REVISION));
    }

    @Test
    void completesAgainstTheDraftDefinitionWithoutADurableRunStore() {
        when(toolRepository.resolve(LICENSE, Set.of())).thenReturn(List.of());
        when(baseAgent.generate(any())).thenReturn(response(
                "{\"answer\":\"done\"}", List.of(), FinishReason.STOP));

        DraftAgentTestResponse result = service(List.of(new BuiltInToolExecutor()))
                .test(LICENSE, request(Map.of(), List.of(), null), "editor-1");

        assertThat(result.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(result.draftRevision()).isEqualTo(REVISION);
        assertThat(result.output().path("answer").asText()).isEqualTo("done");
        assertThat(result.pendingInteractions()).isEmpty();
        assertThat(result.conversation()).isNotEmpty();
        verify(agentRepository).getDraftForTest(LICENSE, AGENT_ID);
    }

    @Test
    void mocksAWriteToolInsideTheRegistryAndFeedsTheMockToTheLlm() {
        PublishedToolDefinition tool = tool("campaign.send", "WRITE");
        ToolExecutor httpExecutor = mock(ToolExecutor.class);
        when(httpExecutor.type()).thenReturn(ToolType.HTTP);
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(
                        stored(AGENT_ID, "DRAFT", 1, Set.of("campaign.send")), REVISION));
        when(toolRepository.resolve(LICENSE, Set.of("campaign.send"))).thenReturn(List.of(tool));
        ToolCall call = new ToolCall("call-1", "campaign.send",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        when(baseAgent.generate(any()))
                .thenReturn(response("", List.of(call), FinishReason.TOOL_CALLS))
                .thenReturn(response("finished", List.of(), FinishReason.STOP));
        JsonNode configuredMock = objectMapper.createObjectNode()
                .put("campaignId", "cmp-1").put("status", "SIMULATED");

        DraftAgentTestResponse result = service(List.of(new BuiltInToolExecutor(), httpExecutor))
                .test(LICENSE, request(Map.of("campaign.send", configuredMock), List.of(), null), "editor-1");

        assertThat(result.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(result.mockedToolCalls()).hasSize(1);
        assertThat(result.mockedToolCalls().getFirst().result().path("status").asText())
                .isEqualTo("SIMULATED");
        verify(httpExecutor, never()).execute(any());
        ArgumentCaptor<BaseAgentRequest> requests = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(baseAgent, org.mockito.Mockito.times(2)).generate(requests.capture());
        assertThat(requests.getAllValues().get(1).messages()).anySatisfy(message ->
                assertThat(message.content()).contains("SIMULATED"));
    }

    @Test
    void executesAnExplicitReadToolNormally() {
        PublishedToolDefinition tool = tool("campaign.get", "READ");
        ToolExecutor httpExecutor = mock(ToolExecutor.class);
        when(httpExecutor.type()).thenReturn(ToolType.HTTP);
        when(httpExecutor.execute(any())).thenReturn(ToolExecutionResult.of(
                objectMapper.createObjectNode().put("status", "ACTIVE")));
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(
                        stored(AGENT_ID, "DRAFT", 1, Set.of("campaign.get")), REVISION));
        when(toolRepository.resolve(LICENSE, Set.of("campaign.get"))).thenReturn(List.of(tool));
        ToolCall call = new ToolCall("call-1", "campaign.get",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        when(baseAgent.generate(any()))
                .thenReturn(response("", List.of(call), FinishReason.TOOL_CALLS))
                .thenReturn(response("finished", List.of(), FinishReason.STOP));

        DraftAgentTestResponse result = service(List.of(new BuiltInToolExecutor(), httpExecutor))
                .test(LICENSE, request(Map.of(), List.of(), null), "editor-1");

        assertThat(result.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(result.mockedToolCalls()).isEmpty();
        verify(httpExecutor).execute(any());
    }

    @Test
    void returnsClarificationAndTreatsTheAnswerAsANewTestRun() {
        when(toolRepository.resolve(LICENSE, Set.of())).thenReturn(List.of());
        ToolCall clarification = new ToolCall(
                "clarify-1",
                BuiltInToolExecutor.REQUEST_CLARIFICATION,
                objectMapper.createObjectNode()
                        .put("category", "LOCATION")
                        .put("question", "Which city should be used?")
                        .put("reason", "A city is required")
                        .put("responseType", "FREE_TEXT"));
        when(baseAgent.generate(any()))
                .thenReturn(response("", List.of(clarification), FinishReason.TOOL_CALLS))
                .thenReturn(response("", List.of(clarification), FinishReason.TOOL_CALLS))
                .thenReturn(response("{\"city\":\"Bengaluru\"}", List.of(), FinishReason.STOP));
        DraftAgentTestService service = service(List.of(new BuiltInToolExecutor()));

        DraftAgentTestResponse first = service.test(
                LICENSE, request(Map.of(), List.of(), null), "editor-1");

        assertThat(first.status()).isEqualTo(DraftAgentTestStatus.NEEDS_INPUT);
        assertThat(first.pendingInteractions()).hasSize(1);
        String token = first.pendingInteractions().getFirst().interactionToken();

        DraftAgentTestResponse second = service.test(
                LICENSE,
                request(Map.of(), List.of(new TestHumanResponse(
                        token, HumanResponseAction.ANSWER,
                        objectMapper.getNodeFactory().textNode("Bengaluru"))), REVISION),
                "editor-1");

        assertThat(second.testRunId()).isNotEqualTo(first.testRunId());
        assertThat(second.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(second.output().path("city").asText()).isEqualTo("Bengaluru");
        ArgumentCaptor<BaseAgentRequest> requests = ArgumentCaptor.forClass(BaseAgentRequest.class);
        verify(baseAgent, org.mockito.Mockito.times(3)).generate(requests.capture());
        assertThat(requests.getAllValues().get(1).messages()).anySatisfy(message ->
                assertThat(message.content()).contains("Bengaluru"));
    }

    @Test
    void honorsApprovalBeforeReturningAMockedWriteResultOnTheNewTestRun() {
        PublishedToolDefinition protectedWrite = new PublishedToolDefinition(
                "tool-send", "campaign.send", "Sends a campaign", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("method", "POST").put("url", "https://api.example/send"),
                objectMapper.createObjectNode()
                        .put("operation", "WRITE")
                        .set("approval", objectMapper.createObjectNode().put("required", true)));
        ToolExecutor httpExecutor = mock(ToolExecutor.class);
        when(httpExecutor.type()).thenReturn(ToolType.HTTP);
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(
                        stored(AGENT_ID, "DRAFT", 1, Set.of("campaign.send")), REVISION));
        when(toolRepository.resolve(LICENSE, Set.of("campaign.send")))
                .thenReturn(List.of(protectedWrite));
        ToolCall call = new ToolCall("send-call", "campaign.send",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        when(baseAgent.generate(any()))
                .thenReturn(response("", List.of(call), FinishReason.TOOL_CALLS))
                .thenReturn(response("", List.of(call), FinishReason.TOOL_CALLS))
                .thenReturn(response("approved simulation", List.of(), FinishReason.STOP));
        DraftAgentTestService service = service(List.of(new BuiltInToolExecutor(), httpExecutor));

        DraftAgentTestResponse first = service.test(
                LICENSE, request(Map.of(), List.of(), null), "editor-1");

        assertThat(first.status()).isEqualTo(DraftAgentTestStatus.NEEDS_INPUT);
        assertThat(first.pendingInteractions().getFirst().type().name()).isEqualTo("TOOL_APPROVAL");

        DraftAgentTestResponse second = service.test(
                LICENSE,
                request(Map.of(), List.of(new TestHumanResponse(
                        first.pendingInteractions().getFirst().interactionToken(),
                        HumanResponseAction.APPROVE, null)), REVISION),
                "editor-1");

        assertThat(second.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(second.mockedToolCalls()).hasSize(1);
        verify(httpExecutor, never()).execute(any());
    }

    @Test
    void runsAPublishedChildTransientlyAndReturnsItsOutputToTheDraftParent() {
        String childId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        PublishedToolDefinition childTool = new PublishedToolDefinition(
                "tool-child", "child.research", "Delegates research", ToolType.CUSTOM_AGENT, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("agentId", childId).put("agentVersion", 2),
                objectMapper.createObjectNode().put("operation", "WRITE"));
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(
                        stored(AGENT_ID, "DRAFT", 1, Set.of("child.research")), REVISION));
        when(agentRepository.get(LICENSE, childId))
                .thenReturn(stored(childId, "PUBLISHED", 2, Set.of()));
        when(toolRepository.resolve(LICENSE, Set.of("child.research"))).thenReturn(List.of(childTool));
        when(toolRepository.resolve(LICENSE, Set.of())).thenReturn(List.of());
        ToolCall childCall = new ToolCall(
                "child-call", "child.research",
                objectMapper.createObjectNode()
                        .put("task", "Research audience")
                        .set("input", objectMapper.createObjectNode().put("campaignId", "cmp-1")));
        when(baseAgent.generate(any()))
                .thenReturn(response("", List.of(childCall), FinishReason.TOOL_CALLS))
                .thenReturn(response("child result", List.of(), FinishReason.STOP))
                .thenReturn(response("parent result", List.of(), FinishReason.STOP));

        DraftAgentTestResponse result = service(List.of(
                new BuiltInToolExecutor(), new CustomAgentToolExecutor()))
                .test(LICENSE, request(Map.of(), List.of(), null), "editor-1");

        assertThat(result.status()).isEqualTo(DraftAgentTestStatus.COMPLETED);
        assertThat(result.output().asText()).isEqualTo("parent result");
        assertThat(result.conversation()).anySatisfy(entry ->
                assertThat(entry.agentId()).isEqualTo(childId));
        assertThat(result.mockedToolCalls()).isEmpty();
    }

    @Test
    void rejectsACustomAgentToolWhoseChildIsNotPublished() {
        String childId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        PublishedToolDefinition childTool = new PublishedToolDefinition(
                "tool-child", "child.research", "Delegates research", ToolType.CUSTOM_AGENT, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("agentId", childId).put("agentVersion", 2),
                objectMapper.createObjectNode());
        when(agentRepository.getDraftForTest(LICENSE, AGENT_ID))
                .thenReturn(new DraftAgentDefinition(
                        stored(AGENT_ID, "DRAFT", 1, Set.of("child.research")), REVISION));
        when(agentRepository.get(LICENSE, childId))
                .thenThrow(new AgentNotPublishedException(childId, "DRAFT"));
        when(toolRepository.resolve(LICENSE, Set.of("child.research"))).thenReturn(List.of(childTool));

        assertThatThrownBy(() -> service(List.of(
                new BuiltInToolExecutor(), new CustomAgentToolExecutor()))
                .test(LICENSE, request(Map.of(), List.of(), null), "editor-1"))
                .isInstanceOf(AgentNotPublishedException.class)
                .hasMessageContaining("DRAFT");

        verify(baseAgent, never()).generate(any());
    }

    private DraftAgentTestService service(List<ToolExecutor> executors) {
        DraftAgentTestProperties testProperties = new DraftAgentTestProperties();
        DraftTestInteractionTokenService tokenService = new DraftTestInteractionTokenService(
                objectMapper, clock, testProperties);
        return new DraftAgentTestService(
                agentRepository, toolRepository, new ToolExecutorRegistry(executors),
                baseAgent, new AgentPromptBuilder(objectMapper), tokenService,
                executionProperties, objectMapper, clock);
    }

    private DraftAgentTestRequest request(
            Map<String, JsonNode> mocks,
            List<TestHumanResponse> responses,
            String revision) {
        return new DraftAgentTestRequest(
                AGENT_ID, "Test the agent", objectMapper.createObjectNode().put("seed", "value"),
                null, null, revision, mocks, responses);
    }

    private StoredAgentDefinition stored(
            String id,
            String status,
            int version,
            Set<String> tools) {
        return new StoredAgentDefinition(id, LICENSE, status, version, new PublishedAgentDefinition(
                "Test Agent", "Tests an agent", "Analyst", "Complete the task",
                List.of(), "JSON", objectMapper.createObjectNode(), objectMapper.createObjectNode(),
                List.of(), tools, objectMapper.createObjectNode()));
    }

    private PublishedToolDefinition tool(String name, String operation) {
        return new PublishedToolDefinition(
                "tool-" + name, name, "Tool " + name, ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("method", "POST").put("url", "https://api.example/test"),
                objectMapper.createObjectNode().put("operation", operation));
    }

    private BaseAgentResponse response(
            String text,
            List<ToolCall> toolCalls,
            FinishReason finishReason) {
        return new BaseAgentResponse(
                "response-1", text, toolCalls, finishReason,
                new TokenUsage(10, 2, 12), Map.of());
    }
}
