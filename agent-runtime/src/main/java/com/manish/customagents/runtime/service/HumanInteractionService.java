package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.entity.AgentRunEntity;
import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import com.manish.customagents.runtime.entity.HumanInteractionRequestEntity;
import com.manish.customagents.runtime.entity.HumanInteractionResponseEntity;
import com.manish.customagents.runtime.entity.HumanResponseBatchEntity;
import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.errors.HumanInteractionConflictException;
import com.manish.customagents.runtime.errors.HumanInteractionNotFoundException;
import com.manish.customagents.runtime.model.AcceptedHumanResponse;
import com.manish.customagents.runtime.model.AgentRunResponse;
import com.manish.customagents.runtime.model.HumanInteractionResolutionResponse;
import com.manish.customagents.runtime.model.HumanInteractionView;
import com.manish.customagents.runtime.model.PendingInteractionSummary;
import com.manish.customagents.runtime.model.RootHumanResponseItem;
import com.manish.customagents.runtime.model.RootHumanResponsesResponse;
import com.manish.customagents.runtime.model.SubmitHumanInteractionResponse;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import com.manish.customagents.runtime.repository.AgentToolInvocationRepository;
import com.manish.customagents.runtime.repository.HumanInteractionRequestRepository;
import com.manish.customagents.runtime.repository.HumanInteractionResponseRepository;
import com.manish.customagents.runtime.repository.HumanResponseBatchRepository;
import com.manish.customagents.runtime.repository.RuntimeOutboxEventRepository;
import com.manish.customagents.runtime.tool.HumanInteractionRequestSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class HumanInteractionService {
    private final HumanInteractionRequestRepository requestRepository;
    private final HumanInteractionResponseRepository responseRepository;
    private final HumanResponseBatchRepository batchRepository;
    private final AgentRunRepository runRepository;
    private final AgentToolInvocationRepository invocationRepository;
    private final RuntimeOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HumanInteractionService(
            HumanInteractionRequestRepository requestRepository,
            HumanInteractionResponseRepository responseRepository,
            HumanResponseBatchRepository batchRepository,
            AgentRunRepository runRepository,
            AgentToolInvocationRepository invocationRepository,
            RuntimeOutboxEventRepository outboxRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.requestRepository = requestRepository;
        this.responseRepository = responseRepository;
        this.batchRepository = batchRepository;
        this.runRepository = runRepository;
        this.invocationRepository = invocationRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public HumanInteractionRequestEntity create(
            AgentRunEntity run,
            AgentToolInvocationEntity invocation,
            HumanInteractionRequestSpec spec,
            String subjectSha256) {
        if (requestRepository.countByRootRunId(run.getRootRunId()) >= spec.maxRequestsPerRootRun()) {
            throw conflict("human-interaction-limit-exceeded",
                    "Root run exceeded the maximum of " + spec.maxRequestsPerRootRun()
                            + " human interactions");
        }
        Instant now = clock.instant();
        HumanAudienceType audience = spec.audienceHint() == HumanAudienceType.CALLER_AGENT
                ? HumanAudienceType.RUN_REQUESTER : spec.audienceHint();
        String assignedUser = audience == HumanAudienceType.RUN_REQUESTER
                ? run.getRequestedBy() : null;
        HumanInteractionRequestEntity request = new HumanInteractionRequestEntity(
                UUID.randomUUID().toString(),
                run.getLicenseCode(),
                run.getRootRunId(),
                run.getId(),
                invocation.getId(),
                spec.type(),
                spec.category(),
                spec.question(),
                spec.reason(),
                spec.responseType(),
                json(spec.request()),
                audience,
                json(spec.audienceValues()),
                assignedUser,
                subjectSha256,
                now.plus(spec.expiresAfter()),
                now);
        return requestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public List<HumanInteractionView> inbox(String licenseCode, String actorId, Set<String> roles) {
        return requestRepository.findByLicenseCodeAndStatusOrderByCreatedAtAsc(
                        licenseCode, HumanInteractionStatus.PENDING).stream()
                .filter(request -> canResolve(request, actorId, roles))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public HumanInteractionView get(String licenseCode, String interactionId, String actorId, Set<String> roles) {
        HumanInteractionRequestEntity request = require(licenseCode, interactionId);
        if (!canResolve(request, actorId, roles)) {
            throw new HumanInteractionNotFoundException(interactionId);
        }
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<PendingInteractionSummary> pendingForRoot(String rootRunId) {
        return requestRepository.findByRootRunIdAndStatusOrderByCreatedAtAsc(
                        rootRunId, HumanInteractionStatus.PENDING).stream()
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResolvedInteraction resolved(String licenseCode, String interactionId) {
        HumanInteractionRequestEntity request = require(licenseCode, interactionId);
        HumanInteractionResponseEntity response = responseRepository.findByInteractionId(interactionId)
                .orElseThrow(() -> conflict("unresolved-human-interaction", "Interaction has no response"));
        JsonNode body = tree(response.getResponseJson());
        return new ResolvedInteraction(
                request.getType(), response.getAction(), body.get("answer"),
                body.path("comment").asText(null), response.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<ResumeTarget> resumeTargets(String rootRunId, List<String> interactionIds) {
        AgentRunEntity root = runRepository.findById(rootRunId)
                .filter(run -> run.getId().equals(run.getRootRunId()))
                .orElseThrow(() -> conflict("agent-run-not-found", "Root agent run not found"));
        return interactionIds.stream()
                .distinct()
                .map(interactionId -> requestRepository
                        .findByIdAndLicenseCode(interactionId, root.getLicenseCode())
                        .filter(request -> request.getRootRunId().equals(rootRunId))
                        .orElseThrow(() -> new HumanInteractionNotFoundException(interactionId)))
                .map(request -> new ResumeTarget(request.getId(), request.getRunId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasPendingForRun(String runId) {
        return requestRepository.existsByRunIdAndStatus(runId, HumanInteractionStatus.PENDING);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public HumanInteractionResolutionResponse respond(
            String licenseCode,
            String interactionId,
            String idempotencyKey,
            String actorId,
            Set<String> roles,
            SubmitHumanInteractionResponse command) {
        HumanInteractionRequestEntity request = require(licenseCode, interactionId);
        RootHumanResponsesResponse batch = respondBatch(
                licenseCode, request.getRootRunId(), idempotencyKey, actorId, roles,
                new SubmitRootHumanResponsesRequest(List.of(new RootHumanResponseItem(
                        interactionId, command.action(), command.answer(), command.comment()))));
        AcceptedHumanResponse accepted = batch.acceptedResponses().getFirst();
        return new HumanInteractionResolutionResponse(
                accepted.interactionId(), accepted.status(), accepted.action(), accepted.actorId(),
                accepted.resolvedAt(), batch.rootRunId(), batch.rootRunStatus());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RootHumanResponsesResponse respondBatch(
            String licenseCode,
            String rootRunId,
            String idempotencyKey,
            String actorId,
            Set<String> roles,
            SubmitRootHumanResponsesRequest command) {
        String requestHash = batchHash(rootRunId, command);
        HumanResponseBatchEntity replay = batchRepository
                .findByLicenseCodeAndIdempotencyKey(licenseCode, idempotencyKey)
                .orElse(null);
        if (replay != null) {
            if (!replay.getRootRunId().equals(rootRunId)
                    || !replay.getActorId().equals(actorId)
                    || !replay.getRequestSha256().equals(requestHash)) {
                throw conflict("idempotency-key-reused",
                        "Idempotency key was already used with a different root, actor, or response batch");
            }
            return read(replay.getResponseJson(), RootHumanResponsesResponse.class);
        }

        AgentRunEntity root = runRepository.findByIdAndLicenseCode(rootRunId, licenseCode)
                .filter(run -> run.getId().equals(run.getRootRunId()))
                .orElseThrow(() -> conflict("agent-run-not-found", "Root agent run not found"));
        validateDistinctInteractionIds(command.responses());
        Instant now = clock.instant();
        String batchId = UUID.randomUUID().toString();
        List<PreparedResponse> prepared = new ArrayList<>();
        List<RootHumanResponseItem> ordered = command.responses().stream()
                .sorted(Comparator.comparing(RootHumanResponseItem::interactionId))
                .toList();

        for (RootHumanResponseItem item : ordered) {
            HumanInteractionRequestEntity request = requestRepository
                    .findForUpdate(item.interactionId(), licenseCode)
                    .filter(candidate -> candidate.getRootRunId().equals(rootRunId))
                    .orElseThrow(() -> new HumanInteractionNotFoundException(item.interactionId()));
            SubmitHumanInteractionResponse response = item.response();
            validateResolvable(request, response, actorId, roles, now);
            AgentToolInvocationEntity invocation = requireBoundInvocation(request);
            enforceSelfApproval(request, invocation, actorId, response);
            prepared.add(new PreparedResponse(request, invocation, response));
        }

        List<HumanInteractionResponseEntity> responseEntities = new ArrayList<>();
        List<AcceptedHumanResponse> accepted = new ArrayList<>();
        for (int index = 0; index < prepared.size(); index++) {
            PreparedResponse item = prepared.get(index);
            item.request().resolve(item.command().action(), now);
            HumanInteractionResponseEntity response = new HumanInteractionResponseEntity(
                    UUID.randomUUID().toString(), batchId, item.request().getId(), licenseCode,
                    item.command().action(), json(responseBody(item.command())), actorId, json(roles),
                    batchId + ":" + index, now);
            responseEntities.add(response);
            applyInvocationDecision(item.invocation(), item.command(), now);
            accepted.add(new AcceptedHumanResponse(
                    item.request().getId(), item.request().getRunId(), item.request().getStatus(),
                    item.command().action(), actorId, now));
        }

        List<HumanInteractionRequestEntity> remaining = requestRepository
                .findByRootRunIdAndStatusOrderByCreatedAtAsc(rootRunId, HumanInteractionStatus.PENDING);
        List<PendingInteractionSummary> visibleRemaining = remaining.stream()
                .filter(request -> canResolve(request, actorId, roles))
                .map(this::summary)
                .toList();
        AgentRunStatus visibleStatus = remaining.isEmpty()
                ? root.getStatus() : AgentRunStatus.WAITING_FOR_HUMAN;
        RootHumanResponsesResponse result = new RootHumanResponsesResponse(
                batchId, rootRunId, visibleStatus, accepted, remaining.size(), visibleRemaining,
                now);

        batchRepository.saveAndFlush(new HumanResponseBatchEntity(
                batchId, licenseCode, rootRunId, idempotencyKey, requestHash,
                json(result), actorId, json(roles), now));
        responseRepository.saveAll(responseEntities);
        ObjectNode payload = objectMapper.createObjectNode().put("batchId", batchId);
        ArrayNode interactionIds = payload.putArray("interactionIds");
        accepted.forEach(response -> interactionIds.add(response.interactionId()));
        outboxRepository.save(new RuntimeOutboxEventEntity(
                batchId, rootRunId, "ROOT_RESUME_REQUESTED", json(payload),
                now.plusSeconds(30), now));
        return result;
    }

    @Transactional(readOnly = true)
    public RootHumanResponsesResponse stableResponse(
            RootHumanResponsesResponse accepted,
            AgentRunResponse run,
            String actorId,
            Set<String> roles) {
        List<HumanInteractionRequestEntity> pending = requestRepository
                .findByRootRunIdAndStatusOrderByCreatedAtAsc(
                        accepted.rootRunId(), HumanInteractionStatus.PENDING);
        List<PendingInteractionSummary> visible = pending.stream()
                .filter(request -> canResolve(request, actorId, roles))
                .map(this::summary)
                .toList();
        AgentRunStatus status = pending.isEmpty()
                ? run.status() : AgentRunStatus.WAITING_FOR_HUMAN;
        return new RootHumanResponsesResponse(
                accepted.batchId(), accepted.rootRunId(), status,
                accepted.acceptedResponses(), pending.size(), visible,
                accepted.acceptedAt(), run.output());
    }

    private void validateAction(
            HumanInteractionRequestEntity request, SubmitHumanInteractionResponse command) {
        if (request.getType() == HumanInteractionType.CLARIFICATION) {
            if (command.action() != HumanResponseAction.ANSWER || command.answer() == null) {
                throw conflict("invalid-human-interaction-response", "Clarification requires an answer");
            }
            return;
        }
        if (command.action() == HumanResponseAction.ANSWER) {
            throw conflict("invalid-human-interaction-response", "Tool approval requires approve or reject");
        }
        if (command.action() == HumanResponseAction.REJECT
                && (command.comment() == null || command.comment().isBlank())) {
            throw conflict("invalid-human-interaction-response", "Rejection requires a comment");
        }
    }

    private void validateResolvable(
            HumanInteractionRequestEntity request,
            SubmitHumanInteractionResponse command,
            String actorId,
            Set<String> roles,
            Instant now) {
        if (!canResolve(request, actorId, roles)) {
            throw new HumanInteractionNotFoundException(request.getId());
        }
        if (!request.getExpiresAt().isAfter(now)) {
            throw conflict("interaction-expired", "Human interaction has expired");
        }
        if (request.getStatus() != HumanInteractionStatus.PENDING) {
            throw conflict("interaction-already-resolved", "Human interaction is already resolved");
        }
        validateAction(request, command);
    }

    private AgentToolInvocationEntity requireBoundInvocation(HumanInteractionRequestEntity request) {
        AgentToolInvocationEntity invocation = invocationRepository.findById(request.getToolInvocationId())
                .orElseThrow(() -> conflict("interaction-subject-changed", "Tool invocation no longer exists"));
        if (!request.getSubjectSha256().equals(invocation.getArgumentsSha256())) {
            throw conflict("interaction-subject-changed", "Tool invocation arguments changed after request creation");
        }
        return invocation;
    }

    private void applyInvocationDecision(
            AgentToolInvocationEntity invocation,
            SubmitHumanInteractionResponse command,
            Instant now) {
        if (command.action() == HumanResponseAction.APPROVE) {
            invocation.approve(now);
        } else if (command.action() == HumanResponseAction.REJECT) {
            invocation.reject(json(objectMapper.createObjectNode()
                    .put("status", "HUMAN_DECLINED")
                    .put("reason", command.comment())), now);
        }
    }

    private void validateDistinctInteractionIds(List<RootHumanResponseItem> responses) {
        Set<String> ids = new HashSet<>();
        if (responses.stream().map(RootHumanResponseItem::interactionId).anyMatch(id -> !ids.add(id))) {
            throw conflict("invalid-human-interaction-response",
                    "Each interaction may appear only once in a response batch");
        }
    }

    private boolean canResolve(HumanInteractionRequestEntity request, String actorId, Set<String> roles) {
        if (roles.contains("AGENT_ADMIN") || roles.contains("RUN_OPERATOR")) return true;
        if (request.getAssignedUserId() != null) return request.getAssignedUserId().equals(actorId);
        List<String> audience = strings(request.getAudienceValuesJson());
        return switch (request.getAudienceType()) {
            case ROLE -> audience.stream().anyMatch(roles::contains);
            case GROUP -> audience.stream().map(value -> "GROUP_" + value).anyMatch(roles::contains);
            default -> false;
        };
    }

    private void enforceSelfApproval(
            HumanInteractionRequestEntity request,
            AgentToolInvocationEntity invocation,
            String actorId,
            SubmitHumanInteractionResponse command) {
        if (request.getType() != HumanInteractionType.TOOL_APPROVAL
                || command.action() != HumanResponseAction.APPROVE) {
            return;
        }
        AgentRunEntity run = runRepository.findById(request.getRunId())
                .orElseThrow(() -> conflict("interaction-subject-changed", "Agent run no longer exists"));
        JsonNode policy = tree(invocation.getApprovalPolicyJson());
        String risk = policy.path("riskLevel").asText("");
        boolean defaultAllowed = !risk.equals("HIGH") && !risk.equals("DESTRUCTIVE");
        boolean selfApprovalAllowed = policy.path("allowSelfApproval").asBoolean(defaultAllowed);
        if (actorId.equals(run.getRequestedBy()) && !selfApprovalAllowed) {
            throw new AccessDeniedException("Requester cannot approve this tool invocation");
        }
    }

    private ObjectNode responseBody(SubmitHumanInteractionResponse command) {
        ObjectNode responseBody = objectMapper.createObjectNode();
        if (command.answer() != null) responseBody.set("answer", command.answer());
        if (command.comment() != null) responseBody.put("comment", command.comment());
        return responseBody;
    }

    private HumanInteractionRequestEntity require(String licenseCode, String id) {
        return requestRepository.findByIdAndLicenseCode(id, licenseCode)
                .orElseThrow(() -> new HumanInteractionNotFoundException(id));
    }

    private HumanInteractionView view(HumanInteractionRequestEntity request) {
        return new HumanInteractionView(
                request.getId(), request.getRootRunId(), request.getRunId(), request.getType(),
                request.getStatus(), request.getCategory(), request.getQuestion(), request.getReason(),
                request.getResponseType(), tree(request.getRequestJson()), request.getAudienceType(),
                strings(request.getAudienceValuesJson()), request.getAssignedUserId(),
                request.getCreatedAt(), request.getExpiresAt(), request.getResolvedAt());
    }

    private PendingInteractionSummary summary(HumanInteractionRequestEntity request) {
        return new PendingInteractionSummary(
                request.getId(), request.getType(), request.getStatus(), request.getRunId(),
                request.getQuestion(), request.getResponseType(), request.getAudienceType(),
                strings(request.getAudienceValuesJson()), request.getAssignedUserId(), request.getExpiresAt());
    }

    private HumanInteractionConflictException conflict(String type, String message) {
        return new HumanInteractionConflictException(type, message);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private JsonNode tree(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private String batchHash(String rootRunId, SubmitRootHumanResponsesRequest command) {
        ObjectNode canonical = objectMapper.createObjectNode().put("rootRunId", rootRunId);
        ArrayNode items = canonical.putArray("responses");
        command.responses().stream()
                .sorted(Comparator.comparing(RootHumanResponseItem::interactionId))
                .forEach(item -> {
                    ObjectNode node = items.addObject()
                            .put("interactionId", item.interactionId())
                            .put("action", item.action().name());
                    if (item.answer() != null) node.set("answer", item.answer());
                    if (item.comment() != null) node.put("comment", item.comment());
                });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    json(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> strings(String json) {
        List<String> values = new ArrayList<>();
        tree(json).forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    public record ResolvedInteraction(
            HumanInteractionType type,
            HumanResponseAction action,
            JsonNode answer,
            String comment,
            Instant resolvedAt) {
    }

    public record ResumeTarget(String interactionId, String runId) {
    }

    private record PreparedResponse(
            HumanInteractionRequestEntity request,
            AgentToolInvocationEntity invocation,
            SubmitHumanInteractionResponse command) {
    }
}
