package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.entity.HumanInteractionRequestEntity;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.enums.ToolInvocationStatus;
import com.manish.customagents.runtime.model.SubmitHumanInteractionResponse;
import com.manish.customagents.runtime.model.RootHumanResponseItem;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import com.manish.customagents.runtime.model.PendingInteractionSummary;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import com.manish.customagents.runtime.repository.HumanInteractionRequestRepository;
import com.manish.customagents.runtime.repository.HumanInteractionResponseRepository;
import com.manish.customagents.runtime.repository.HumanResponseBatchRepository;
import com.manish.customagents.runtime.repository.RuntimeOutboxEventRepository;
import com.manish.customagents.runtime.tool.HumanInteractionRequestSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class HumanInteractionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HumanInteractionRequestRepository requests = mock(HumanInteractionRequestRepository.class);
    private final HumanInteractionResponseRepository responses = mock(HumanInteractionResponseRepository.class);
    private final HumanResponseBatchRepository batches = mock(HumanResponseBatchRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentToolInvocationRepository invocations = mock(AgentToolInvocationRepository.class);
    private final RuntimeOutboxEventRepository outbox = mock(RuntimeOutboxEventRepository.class);
    private final HumanInteractionService service = new HumanInteractionService(
            requests, responses, batches, runs, invocations, outbox, objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void approvesExactPersistedToolInvocationAndQueuesResume() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity invocation = invocation();
        ReflectionTestUtils.setField(invocation, "id", 42L);
        invocation.waitForApproval(NOW);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity interaction = service.create(
                run, invocation,
                new HumanInteractionRequestSpec(
                        HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION", "Approve?", "Protected",
                        HumanResponseType.APPROVAL, objectMapper.createObjectNode(),
                        HumanAudienceType.RUN_REQUESTER, List.of(), Duration.ofHours(24)),
                "binding");
        when(requests.findByIdAndLicenseCode(interaction.getId(), "tenant-1"))
                .thenReturn(Optional.of(interaction));
        when(requests.findForUpdate(interaction.getId(), "tenant-1"))
                .thenReturn(Optional.of(interaction));
        when(batches.findByLicenseCodeAndIdempotencyKey("tenant-1", "key-1"))
                .thenReturn(Optional.empty());
        when(invocations.findById(42L)).thenReturn(Optional.of(invocation));
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(runs.findByIdAndLicenseCode("run-1", "tenant-1")).thenReturn(Optional.of(run));

        var result = service.respond(
                "tenant-1", interaction.getId(), "key-1", "user-1", Set.of("RUN_REQUESTER"),
                new SubmitHumanInteractionResponse(HumanResponseAction.APPROVE, null, "Reviewed"));

        assertThat(result.status()).isEqualTo(HumanInteractionStatus.APPROVED);
        assertThat(invocation.getStatus()).isEqualTo(ToolInvocationStatus.APPROVED);
        verify(responses).saveAll(any());
        verify(outbox).save(any());
    }

    @Test
    void preventsRequesterSelfApprovalForHighRiskInvocation() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity invocation = new AgentToolInvocationEntity(
                "run-1", 1, "call-1", "campaign.send", "tool-1", 1, "HTTP",
                "{\"campaignId\":\"c1\"}", "binding",
                "{\"required\":true,\"riskLevel\":\"HIGH\"}", NOW);
        ReflectionTestUtils.setField(invocation, "id", 43L);
        invocation.waitForApproval(NOW);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity interaction = service.create(
                run, invocation,
                new HumanInteractionRequestSpec(
                        HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION", "Approve?", "Protected",
                        HumanResponseType.APPROVAL, objectMapper.createObjectNode(),
                        HumanAudienceType.RUN_REQUESTER, List.of(), Duration.ofHours(24)),
                "binding");
        when(requests.findByIdAndLicenseCode(interaction.getId(), "tenant-1"))
                .thenReturn(Optional.of(interaction));
        when(requests.findForUpdate(interaction.getId(), "tenant-1"))
                .thenReturn(Optional.of(interaction));
        when(batches.findByLicenseCodeAndIdempotencyKey("tenant-1", "key-2"))
                .thenReturn(Optional.empty());
        when(invocations.findById(43L)).thenReturn(Optional.of(invocation));
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(runs.findByIdAndLicenseCode("run-1", "tenant-1")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.respond(
                "tenant-1", interaction.getId(), "key-2", "user-1", Set.of("RUN_REQUESTER"),
                new SubmitHumanInteractionResponse(HumanResponseAction.APPROVE, null, "Reviewed")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void resolvesGroupAudienceFromJwtGroupAuthority() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity invocation = invocation();
        ReflectionTestUtils.setField(invocation, "id", 44L);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity interaction = service.create(
                run, invocation,
                new HumanInteractionRequestSpec(
                        HumanInteractionType.CLARIFICATION, "MISSING_INPUT", "Which region?", "Required",
                        HumanResponseType.FREE_TEXT, objectMapper.createObjectNode(),
                        HumanAudienceType.GROUP, List.of("campaign-owners"), Duration.ofHours(24)),
                "binding");
        when(requests.findByLicenseCodeAndStatusOrderByCreatedAtAsc(
                "tenant-1", HumanInteractionStatus.PENDING)).thenReturn(List.of(interaction));

        assertThat(service.inbox("tenant-1", "reviewer", Set.of("GROUP_campaign-owners")))
                .extracting(view -> view.interactionId())
                .containsExactly(interaction.getId());
    }

    @Test
    void acceptsMultipleResponsesAtomicallyAndEmitsOneRootResumeCommand() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity firstInvocation = invocation();
        AgentToolInvocationEntity secondInvocation = new AgentToolInvocationEntity(
                "run-1", 1, "call-2", "campaign.preview", "tool-2", 1, "HTTP",
                "{}", "binding-2", "{\"required\":true}", NOW);
        ReflectionTestUtils.setField(firstInvocation, "id", 51L);
        ReflectionTestUtils.setField(secondInvocation, "id", 52L);
        firstInvocation.waitForApproval(NOW);
        secondInvocation.waitForApproval(NOW);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity first = service.create(
                run, firstInvocation,
                approvalSpec("Approve send?"), "binding");
        HumanInteractionRequestEntity second = service.create(
                run, secondInvocation,
                approvalSpec("Approve preview?"), "binding-2");
        when(batches.findByLicenseCodeAndIdempotencyKey("tenant-1", "batch-key"))
                .thenReturn(Optional.empty());
        when(runs.findByIdAndLicenseCode("run-1", "tenant-1")).thenReturn(Optional.of(run));
        when(requests.findForUpdate(first.getId(), "tenant-1")).thenReturn(Optional.of(first));
        when(requests.findForUpdate(second.getId(), "tenant-1")).thenReturn(Optional.of(second));
        when(invocations.findById(51L)).thenReturn(Optional.of(firstInvocation));
        when(invocations.findById(52L)).thenReturn(Optional.of(secondInvocation));
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(requests.findByRootRunIdAndStatusOrderByCreatedAtAsc(
                "run-1", HumanInteractionStatus.PENDING)).thenReturn(List.of());

        var result = service.respondBatch(
                "tenant-1", "run-1", "batch-key", "user-1", Set.of("RUN_REQUESTER"),
                new SubmitRootHumanResponsesRequest(List.of(
                        new RootHumanResponseItem(first.getId(), HumanResponseAction.APPROVE, null, "yes"),
                        new RootHumanResponseItem(second.getId(), HumanResponseAction.REJECT, null, "not now"))));

        assertThat(result.acceptedResponses()).hasSize(2);
        assertThat(firstInvocation.getStatus()).isEqualTo(ToolInvocationStatus.APPROVED);
        assertThat(secondInvocation.getStatus()).isEqualTo(ToolInvocationStatus.REJECTED);
        verify(batches).saveAndFlush(any());
        verify(responses).saveAll(any());
        verify(outbox, times(1)).save(any());
    }

    @Test
    void rejectsWholeBatchBeforeMutatingAnyInteraction() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity firstInvocation = invocation();
        AgentToolInvocationEntity secondInvocation = new AgentToolInvocationEntity(
                "run-1", 1, "call-2", "campaign.preview", "tool-2", 1, "HTTP",
                "{}", "binding-2", "{\"required\":true}", NOW);
        ReflectionTestUtils.setField(firstInvocation, "id", 61L);
        ReflectionTestUtils.setField(secondInvocation, "id", 62L);
        firstInvocation.waitForApproval(NOW);
        secondInvocation.waitForApproval(NOW);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity first = service.create(
                run, firstInvocation, approvalSpec("Approve send?"), "binding");
        HumanInteractionRequestEntity expired = service.create(
                run, secondInvocation,
                new HumanInteractionRequestSpec(
                        HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION", "Approve preview?", "Protected",
                        HumanResponseType.APPROVAL, objectMapper.createObjectNode(),
                        HumanAudienceType.RUN_REQUESTER, List.of(), Duration.ZERO),
                "binding-2");
        when(batches.findByLicenseCodeAndIdempotencyKey("tenant-1", "invalid-batch"))
                .thenReturn(Optional.empty());
        when(runs.findByIdAndLicenseCode("run-1", "tenant-1")).thenReturn(Optional.of(run));
        when(requests.findForUpdate(first.getId(), "tenant-1")).thenReturn(Optional.of(first));
        when(requests.findForUpdate(expired.getId(), "tenant-1")).thenReturn(Optional.of(expired));
        when(invocations.findById(61L)).thenReturn(Optional.of(firstInvocation));

        assertThatThrownBy(() -> service.respondBatch(
                "tenant-1", "run-1", "invalid-batch", "user-1", Set.of("RUN_REQUESTER"),
                new SubmitRootHumanResponsesRequest(List.of(
                        new RootHumanResponseItem(first.getId(), HumanResponseAction.APPROVE, null, "yes"),
                        new RootHumanResponseItem(expired.getId(), HumanResponseAction.REJECT, null, "expired")))))
                .isInstanceOf(com.manish.customagents.runtime.errors.HumanInteractionConflictException.class);

        assertThat(first.getStatus()).isEqualTo(HumanInteractionStatus.PENDING);
        assertThat(firstInvocation.getStatus()).isEqualTo(ToolInvocationStatus.WAITING_FOR_APPROVAL);
        verify(responses, never()).saveAll(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void allowsAResponseBatchToResolveOnlyPartOfTheRootPendingSet() {
        AgentRunEntity run = run();
        AgentToolInvocationEntity answeredInvocation = invocation();
        AgentToolInvocationEntity remainingInvocation = new AgentToolInvocationEntity(
                "run-1", 1, "call-2", "campaign.preview", "tool-2", 1, "HTTP",
                "{}", "binding-2", "{\"required\":true}", NOW);
        ReflectionTestUtils.setField(answeredInvocation, "id", 71L);
        ReflectionTestUtils.setField(remainingInvocation, "id", 72L);
        answeredInvocation.waitForApproval(NOW);
        remainingInvocation.waitForApproval(NOW);
        when(requests.save(any())).thenAnswer(call -> call.getArgument(0));
        HumanInteractionRequestEntity answered = service.create(
                run, answeredInvocation, approvalSpec("Approve send?"), "binding");
        HumanInteractionRequestEntity remaining = service.create(
                run, remainingInvocation, approvalSpec("Approve preview?"), "binding-2");
        when(batches.findByLicenseCodeAndIdempotencyKey("tenant-1", "partial-batch"))
                .thenReturn(Optional.empty());
        when(runs.findByIdAndLicenseCode("run-1", "tenant-1")).thenReturn(Optional.of(run));
        when(requests.findForUpdate(answered.getId(), "tenant-1")).thenReturn(Optional.of(answered));
        when(invocations.findById(71L)).thenReturn(Optional.of(answeredInvocation));
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(requests.findByRootRunIdAndStatusOrderByCreatedAtAsc(
                "run-1", HumanInteractionStatus.PENDING)).thenReturn(List.of(remaining));

        var result = service.respondBatch(
                "tenant-1", "run-1", "partial-batch", "user-1", Set.of("RUN_REQUESTER"),
                new SubmitRootHumanResponsesRequest(List.of(
                        new RootHumanResponseItem(answered.getId(), HumanResponseAction.APPROVE, null, "yes"))));

        assertThat(result.acceptedResponses()).hasSize(1);
        assertThat(result.pendingInteractionCount()).isEqualTo(1);
        assertThat(result.pendingInteractions()).extracting(PendingInteractionSummary::interactionId)
                .containsExactly(remaining.getId());
    }

    private HumanInteractionRequestSpec approvalSpec(String question) {
        return new HumanInteractionRequestSpec(
                HumanInteractionType.TOOL_APPROVAL, "TOOL_EXECUTION", question, "Protected",
                HumanResponseType.APPROVAL, objectMapper.createObjectNode(),
                HumanAudienceType.RUN_REQUESTER, List.of(), Duration.ofHours(24));
    }

    private AgentRunEntity run() {
        return new AgentRunEntity(
                "run-1", "run-1", null, null, "tenant-1", "user-1", "agent-1", 1,
                "GOOGLE_GEMINI", "gemini", "{}", NOW);
    }

    private AgentToolInvocationEntity invocation() {
        return new AgentToolInvocationEntity(
                "run-1", 1, "call-1", "campaign.send", "tool-1", 1, "HTTP",
                "{\"campaignId\":\"c1\"}", "binding", "{\"required\":true}", NOW);
    }
}
