package com.manish.customagents.runtime.service;

import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.HumanInteractionResolutionResponse;
import com.manish.customagents.runtime.model.RootHumanResponseItem;
import com.manish.customagents.runtime.model.RootHumanResponsesResponse;
import com.manish.customagents.runtime.model.SubmitHumanInteractionResponse;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Service;

@Service
public class RootHumanResponseService {
    private static final Duration CONCURRENT_REPLAY_WAIT = Duration.ofMinutes(2);

    private final HumanInteractionService humanInteractions;
    private final ResumeOutboxStore outbox;
    private final ResumeOutboxProcessor processor;
    private final AgentExecutionService executionService;

    public RootHumanResponseService(
            HumanInteractionService humanInteractions,
            ResumeOutboxStore outbox,
            ResumeOutboxProcessor processor,
            AgentExecutionService executionService) {
        this.humanInteractions = humanInteractions;
        this.outbox = outbox;
        this.processor = processor;
        this.executionService = executionService;
    }

    public RootHumanResponsesResponse respond(
            String licenseCode,
            String rootRunId,
            String idempotencyKey,
            String actorId,
            Set<String> roles,
            SubmitRootHumanResponsesRequest command) {
        RootHumanResponsesResponse accepted = humanInteractions.respondBatch(
                licenseCode, rootRunId, idempotencyKey, actorId, roles, command);
        resumeSynchronously(accepted.batchId());
        AgentRunResponse stableRun = executionService.get(licenseCode, rootRunId);
        return humanInteractions.stableResponse(accepted, stableRun, actorId, roles);
    }

    public HumanInteractionResolutionResponse respondSingle(
            String licenseCode,
            String interactionId,
            String idempotencyKey,
            String actorId,
            Set<String> roles,
            SubmitHumanInteractionResponse command) {
        String rootRunId = humanInteractions.get(
                licenseCode, interactionId, actorId, roles).rootRunId();
        RootHumanResponsesResponse result = respond(
                licenseCode, rootRunId, idempotencyKey, actorId, roles,
                new SubmitRootHumanResponsesRequest(List.of(new RootHumanResponseItem(
                        interactionId, command.action(), command.answer(), command.comment()))));
        var accepted = result.acceptedResponses().getFirst();
        return new HumanInteractionResolutionResponse(
                accepted.interactionId(), accepted.status(), accepted.action(), accepted.actorId(),
                accepted.resolvedAt(), result.rootRunId(), result.rootRunStatus());
    }

    private void resumeSynchronously(String outboxId) {
        Instant deadline = Instant.now().plus(CONCURRENT_REPLAY_WAIT);
        while (true) {
            Optional<RuntimeOutboxEventEntity> claimed = outbox.claim(outboxId);
            if (claimed.isPresent()) {
                try {
                    processor.process(claimed.get());
                    outbox.complete(outboxId);
                    return;
                } catch (Exception exception) {
                    outbox.retry(outboxId, exception.getMessage());
                    throw exception instanceof RuntimeException runtime
                            ? runtime
                            : new AgentExecutionException("Unable to resume root agent run", exception);
                }
            }

            String status = outbox.status(outboxId)
                    .orElseThrow(() -> new AgentExecutionException(
                            "Root resume command was not persisted: " + outboxId));
            if ("PUBLISHED".equals(status)) return;
            if ("FAILED".equals(status)) {
                throw new AgentExecutionException("Root resume command failed: " + outboxId);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AgentExecutionException(
                        "Timed out waiting for concurrent root resume: " + outboxId);
            }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
    }
}
