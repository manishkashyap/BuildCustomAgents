package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.AgentRunCheckpointEntity;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.errors.HumanInteractionConflictException;
import com.manish.customagents.runtime.model.AddHumanInstructionRequest;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.HumanInstructionResponse;
import com.manish.customagents.runtime.repository.AgentRunCheckpointRepository;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.HumanInteractionRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunControlService {
    private static final TypeReference<List<AgentMessage>> MESSAGES = new TypeReference<>() {};
    private final AgentRunRepository runs;
    private final AgentRunCheckpointRepository checkpoints;
    private final HumanInteractionRequestRepository interactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AgentRunControlService(
            AgentRunRepository runs, AgentRunCheckpointRepository checkpoints,
            HumanInteractionRequestRepository interactions, ObjectMapper objectMapper, Clock clock) {
        this.runs = runs;
        this.checkpoints = checkpoints;
        this.interactions = interactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public HumanInstructionResponse addInstruction(
            String licenseCode, String rootRunId, AddHumanInstructionRequest command) {
        AgentRunEntity root = requireRun(licenseCode, rootRunId);
        String targetId = command.targetRunId() == null ? rootRunId : command.targetRunId();
        AgentRunEntity target = requireRun(licenseCode, targetId);
        if (!target.getRootRunId().equals(root.getRootRunId()) || target.isTerminal()) {
            throw conflict("run-already-terminal", "Target run cannot accept a correction");
        }
        AgentRunCheckpointEntity checkpoint = checkpoints.findById(targetId)
                .orElseThrow(() -> conflict("run-not-waiting", "Run has no durable checkpoint yet"));
        try {
            List<AgentMessage> messages = new ArrayList<>(
                    objectMapper.readValue(checkpoint.getMessagesJson(), MESSAGES));
            messages.add(AgentMessage.user("HUMAN CORRECTION\n" + command.message().strip()));
            checkpoint.update(
                    checkpoint.getNextTurnNumber(), objectMapper.writeValueAsString(messages),
                    checkpoint.getPendingWorkJson(), checkpoint.getTokenUsageJson(),
                    checkpoint.getExecutionScopeJson(), clock.instant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to append human instruction", exception);
        }
        Instant now = clock.instant();
        return new HumanInstructionResponse(
                UUID.randomUUID().toString(), rootRunId, targetId, "QUEUED", now);
    }

    @Transactional
    public AgentRunEntity cancel(String licenseCode, String runId, String scope) {
        AgentRunEntity selected = requireRun(licenseCode, runId);
        String rootId = scope.equals("THIS_RUN") ? selected.getId() : selected.getRootRunId();
        Instant now = clock.instant();
        List<AgentRunEntity> affected = scope.equals("THIS_RUN")
                ? List.of(selected) : runs.findByRootRunId(rootId);
        affected.forEach(run -> run.cancel(now));
        interactions.findByRootRunIdAndStatusOrderByCreatedAtAsc(
                        selected.getRootRunId(), HumanInteractionStatus.PENDING)
                .forEach(interaction -> interaction.cancel(now));
        return selected;
    }

    private AgentRunEntity requireRun(String licenseCode, String runId) {
        return runs.findByIdAndLicenseCode(runId, licenseCode)
                .orElseThrow(() -> conflict("agent-run-not-found", "Agent run not found"));
    }

    private HumanInteractionConflictException conflict(String type, String message) {
        return new HumanInteractionConflictException(type, message);
    }
}
