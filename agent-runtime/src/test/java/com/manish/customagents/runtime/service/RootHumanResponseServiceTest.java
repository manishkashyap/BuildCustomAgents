package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.RootHumanResponseItem;
import com.manish.customagents.runtime.model.RootHumanResponsesResponse;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RootHumanResponseServiceTest {
    private final HumanInteractionService human = mock(HumanInteractionService.class);
    private final ResumeOutboxStore outbox = mock(ResumeOutboxStore.class);
    private final ResumeOutboxProcessor processor = mock(ResumeOutboxProcessor.class);
    private final AgentExecutionService execution = mock(AgentExecutionService.class);
    private final RootHumanResponseService service =
            new RootHumanResponseService(human, outbox, processor, execution);

    @Test
    void resumesBeforeReturningTheNextStableRootState() throws Exception {
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        var command = new SubmitRootHumanResponsesRequest(List.of(
                new RootHumanResponseItem("interaction-1", HumanResponseAction.ANSWER,
                        new ObjectMapper().getNodeFactory().textNode("Bengaluru"), null)));
        var accepted = new RootHumanResponsesResponse(
                "batch-1", "root-1", AgentRunStatus.WAITING_FOR_HUMAN,
                List.of(), 0, List.of(), now);
        RuntimeOutboxEventEntity event = new RuntimeOutboxEventEntity(
                "batch-1", "root-1", "ROOT_RESUME_REQUESTED",
                "{\"interactionIds\":[\"interaction-1\"]}", now);
        AgentRunResponse completed = new AgentRunResponse(
                "root-1", "root-1", "agent-1", 1, AgentRunStatus.SUCCEEDED,
                "GOOGLE_GEMINI", "gemini", new ObjectMapper().createObjectNode().put("done", true),
                TokenUsage.ZERO, List.of(), now, now, now);
        RootHumanResponsesResponse stable = new RootHumanResponsesResponse(
                "batch-1", "root-1", AgentRunStatus.SUCCEEDED,
                List.of(), 0, List.of(), now, completed.output());

        when(human.respondBatch(
                eq("tenant-1"), eq("root-1"), eq("key-1"), eq("user-1"),
                eq(Set.of("RUN_REQUESTER")), eq(command))).thenReturn(accepted);
        when(outbox.claim("batch-1")).thenReturn(Optional.of(event));
        when(execution.get("tenant-1", "root-1")).thenReturn(completed);
        when(human.stableResponse(accepted, completed, "user-1", Set.of("RUN_REQUESTER")))
                .thenReturn(stable);

        RootHumanResponsesResponse result = service.respond(
                "tenant-1", "root-1", "key-1", "user-1",
                Set.of("RUN_REQUESTER"), command);

        assertThat(result.rootRunStatus()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(result.output()).isEqualTo(completed.output());
        InOrder order = inOrder(human, outbox, processor, execution);
        order.verify(human).respondBatch(any(), any(), any(), any(), any(), any());
        order.verify(outbox).claim("batch-1");
        order.verify(processor).process(event);
        order.verify(outbox).complete("batch-1");
        order.verify(execution).get("tenant-1", "root-1");
        order.verify(human).stableResponse(accepted, completed, "user-1", Set.of("RUN_REQUESTER"));
    }
}
