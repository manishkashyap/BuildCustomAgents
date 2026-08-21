package com.manish.customagents.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.agent.entity.AgentAuditEventEntity;
import com.manish.customagents.agent.entity.AgentCopyRequestEntity;
import com.manish.customagents.agent.entity.AgentVersionEntity;
import com.manish.customagents.agent.entity.CustomAgentEntity;
import com.manish.customagents.agent.enums.AgentLineageStatus;
import com.manish.customagents.agent.enums.AgentStatus;
import com.manish.customagents.agent.enums.AgentVersionStatus;
import com.manish.customagents.agent.model.AgentStatusResponse;
import com.manish.customagents.agent.model.CopyCustomAgentRequest;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.agent.model.CustomAgentResponse;
import com.manish.customagents.agent.model.RetirementEligibilityResponse;
import com.manish.customagents.agent.repository.AgentAuditEventRepository;
import com.manish.customagents.agent.repository.AgentCopyRequestRepository;
import com.manish.customagents.agent.repository.AgentVersionRepository;
import com.manish.customagents.agent.repository.CustomAgentRepository;
import com.manish.customagents.error.AgentCopyConflictException;
import com.manish.customagents.error.AgentDependencyException;
import com.manish.customagents.error.AgentNotFoundException;
import com.manish.customagents.error.AgentRetirementBlockedException;
import com.manish.customagents.error.DuplicateAgentNameException;
import com.manish.customagents.error.InvalidAgentStatusTransitionException;
import com.manish.customagents.tool.entity.CustomToolEntity;
import com.manish.customagents.tool.enums.ToolStatus;
import com.manish.customagents.tool.enums.ToolType;
import com.manish.customagents.tool.repository.CustomToolRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomAgentService {
    private static final Set<String> PATCH_FIELDS = Set.of(
            "name", "description", "role", "instructions", "rules", "outputFormat",
            "outputSchema", "context", "examples", "allowedTools", "humanInteractionPolicy");
    private static final Set<String> REQUIRED_PATCH_FIELDS = Set.of(
            "name", "role", "instructions", "rules", "examples", "allowedTools");

    private final CustomAgentRepository repository;
    private final AgentVersionRepository versionRepository;
    private final CustomToolRepository toolRepository;
    private final AgentCopyRequestRepository copyRepository;
    private final AgentAuditEventRepository auditRepository;
    private final AgentDefinitionJsonMapper definitionJsonMapper;
    private final RuntimeRetirementClient runtimeRetirementClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CustomAgentService(
            CustomAgentRepository repository,
            AgentVersionRepository versionRepository,
            CustomToolRepository toolRepository,
            AgentCopyRequestRepository copyRepository,
            AgentAuditEventRepository auditRepository,
            AgentDefinitionJsonMapper definitionJsonMapper,
            RuntimeRetirementClient runtimeRetirementClient,
            ObjectMapper objectMapper,
            Validator validator,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.versionRepository = versionRepository;
        this.toolRepository = toolRepository;
        this.copyRepository = copyRepository;
        this.auditRepository = auditRepository;
        this.definitionJsonMapper = definitionJsonMapper;
        this.runtimeRetirementClient = runtimeRetirementClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** An agent identity paired with the one version being reported on. */
    private record Snapshot(CustomAgentEntity lineage, AgentVersionEntity version) {
    }

    @Transactional
    public CustomAgentResponse create(String licenseCode, CreateCustomAgentRequest request,
            String actorId, String changeReason) {
        String tenant = licenseCode.strip();
        String name = request.name().strip();
        CreateCustomAgentRequest definition = normalizeDefinition(request, name);
        Snapshot created = createLineageWithDraft(
                tenant, definition, actorId, changeReason, clock.instant());
        audit(created, "CREATED", actorId, changeReason);
        return response(created, definition);
    }

    @Transactional(readOnly = true)
    public List<CustomAgentResponse> list(String licenseCode) {
        List<CustomAgentEntity> lineages = repository
                .findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(licenseCode.strip());
        Map<String, List<AgentVersionEntity>> versions = versionsByAgentId(
                lineages.stream().map(CustomAgentEntity::getId).toList());
        return lineages.stream()
                .map(lineage -> response(new Snapshot(
                        lineage, reportable(lineage, versions.getOrDefault(lineage.getId(), List.of())))))
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomAgentResponse get(String licenseCode, String agentId) {
        return response(requireSnapshot(licenseCode, agentId));
    }

    @Transactional
    public CustomAgentResponse updateDraft(String licenseCode, String agentId, JsonNode patch,
            String actorId, String changeReason) {
        CustomAgentEntity lineage = require(licenseCode, agentId);
        AgentVersionEntity draft = requireDraft(lineage);
        CreateCustomAgentRequest updated = merge(draft.getDefinitionJson(), patch);
        String normalizedName = normalizeName(updated.name());
        if (!lineage.getNormalizedName().equals(normalizedName)
                && repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                        lineage.getLicenseCode(), normalizedName)) {
            throw new DuplicateAgentNameException(updated.name());
        }
        Instant now = clock.instant();
        String reason = normalizeNullable(changeReason);
        lineage.renameDraft(updated.name(), normalizedName, updated.description(), actorId, reason, now);
        draft.updateDefinition(definitionJsonMapper.write(updated), actorId, reason, now);
        try {
            repository.saveAndFlush(lineage);
            versionRepository.saveAndFlush(draft);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateAgentNameException(updated.name());
        }
        Snapshot snapshot = new Snapshot(lineage, draft);
        audit(snapshot, "UPDATED", actorId, changeReason);
        return response(snapshot, updated);
    }

    @Transactional
    public CustomAgentResponse copy(String licenseCode, String sourceAgentId,
            CopyCustomAgentRequest request, String idempotencyKey, String actorId, String changeReason) {
        String tenant = licenseCode.strip();
        String hash = sha256(sourceAgentId.strip() + "\n" + request.name().strip());
        AgentCopyRequestEntity replay = copyRepository
                .findByLicenseCodeAndIdempotencyKey(tenant, idempotencyKey).orElse(null);
        if (replay != null) {
            if (!replay.getRequestSha256().equals(hash)) {
                throw new AgentCopyConflictException(
                        "Idempotency key was already used for a different agent copy request");
            }
            return response(requireSnapshot(tenant, replay.getCopiedAgentId()));
        }

        Snapshot source = requireSnapshot(tenant, sourceAgentId);
        AgentVersionStatus sourceStatus = source.version().getStatus();
        if (sourceStatus != AgentVersionStatus.PUBLISHED
                && sourceStatus != AgentVersionStatus.SUPERSEDED
                && sourceStatus != AgentVersionStatus.RETIRED) {
            throw new InvalidAgentStatusTransitionException(apiStatus(source), AgentStatus.DRAFT);
        }
        CreateCustomAgentRequest sourceDefinition =
                definitionJsonMapper.read(source.version().getDefinitionJson());
        CreateCustomAgentRequest copiedDefinition = normalizeDefinition(new CreateCustomAgentRequest(
                request.name(), sourceDefinition.description(), sourceDefinition.role(),
                sourceDefinition.instructions(), sourceDefinition.rules(), sourceDefinition.outputFormat(),
                sourceDefinition.outputSchema(), sourceDefinition.context(), sourceDefinition.examples(),
                sourceDefinition.allowedTools(), sourceDefinition.humanInteractionPolicy()),
                request.name().strip());
        Instant now = clock.instant();
        Snapshot copied = createLineageWithDraft(tenant, copiedDefinition, actorId, changeReason, now);
        copyRepository.saveAndFlush(new AgentCopyRequestEntity(
                idempotencyKey, tenant, hash, copied.lineage().getId(), actorId, now));
        audit(copied, "COPIED", actorId, changeReason);
        return response(copied, copiedDefinition);
    }

    public AgentStatusResponse updateStatus(String licenseCode, String agentId,
            AgentStatus requestedStatus, String actorId, String changeReason) {
        if (requestedStatus == AgentStatus.PUBLISHED) {
            return transactions.execute(status -> publish(
                    licenseCode, agentId, actorId, changeReason));
        }
        if (requestedStatus == AgentStatus.RETIRED) {
            return retire(licenseCode, agentId, actorId, changeReason);
        }
        AgentStatus current = transactions.execute(
                status -> apiStatus(requireSnapshot(licenseCode, agentId)));
        throw new InvalidAgentStatusTransitionException(current, requestedStatus);
    }

    @Scheduled(fixedDelayString = "${agents.retirement.recovery-interval-ms:60000}")
    @Transactional
    public void recoverStaleRetirements() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(5));
        for (CustomAgentEntity lineage : repository
                .findByStatusAndUpdatedAtBeforeAndDeletedFalse(AgentLineageStatus.RETIRING, cutoff)) {
            lineage.restoreActive("system", "Recovered interrupted retirement", clock.instant());
            repository.save(lineage);
            audit(snapshotOf(lineage), "RETIREMENT_RECOVERED", "system",
                    "Recovered interrupted retirement");
        }
    }

    private AgentStatusResponse publish(String licenseCode, String agentId,
            String actorId, String changeReason) {
        CustomAgentEntity lineage = require(licenseCode, agentId);
        if (lineage.getStatus() != AgentLineageStatus.ACTIVE) {
            throw new InvalidAgentStatusTransitionException(
                    apiStatus(snapshotOf(lineage)), AgentStatus.PUBLISHED);
        }
        if (!lineage.hasDraft()) {
            if (lineage.hasActiveVersion()) {
                return statusResponse(snapshotOf(lineage));
            }
            throw new InvalidAgentStatusTransitionException(
                    apiStatus(snapshotOf(lineage)), AgentStatus.PUBLISHED);
        }
        AgentVersionEntity draft = requireDraft(lineage);
        validatePublishedDependencies(lineage, draft);

        Instant now = clock.instant();
        String reason = normalizeNullable(changeReason);
        Integer previousActive = lineage.getActiveVersion();
        draft.publish(actorId, reason, now);
        versionRepository.saveAndFlush(draft);
        if (previousActive != null && previousActive != draft.getVersion()) {
            AgentVersionEntity superseded = requireVersion(lineage, previousActive);
            superseded.supersede(actorId, reason, now);
            versionRepository.saveAndFlush(superseded);
        }
        lineage.promote(draft.getVersion(), actorId, reason, now);
        repository.saveAndFlush(lineage);

        Snapshot snapshot = new Snapshot(lineage, draft);
        audit(snapshot, "PUBLISHED", actorId, changeReason);
        return statusResponse(snapshot);
    }

    private AgentStatusResponse retire(String licenseCode, String agentId,
            String actorId, String changeReason) {
        AgentStatusResponse marked = transactions.execute(status -> {
            CustomAgentEntity lineage = require(licenseCode, agentId);
            if (lineage.getStatus() == AgentLineageStatus.RETIRED) {
                return statusResponse(snapshotOf(lineage));
            }
            if (lineage.getStatus() != AgentLineageStatus.ACTIVE || !lineage.hasActiveVersion()) {
                throw new InvalidAgentStatusTransitionException(
                        apiStatus(snapshotOf(lineage)), AgentStatus.RETIRED);
            }
            lineage.beginRetiring(actorId, normalizeNullable(changeReason), clock.instant());
            repository.saveAndFlush(lineage);
            return statusResponse(snapshotOf(lineage));
        });
        if (marked.status() == AgentStatus.RETIRED) return marked;

        try {
            List<Map<String, String>> dependents = publishedDependents(licenseCode, agentId);
            RetirementEligibilityResponse runtime = runtimeRetirementClient.check(licenseCode, agentId);
            if (!dependents.isEmpty() || !runtime.eligible()) {
                throw new AgentRetirementBlockedException(
                        runtime.activeRunCount(), runtime.activeRunsByStatus(), dependents);
            }
            return transactions.execute(status -> {
                CustomAgentEntity lineage = require(licenseCode, agentId);
                Instant now = clock.instant();
                String reason = normalizeNullable(changeReason);
                lineage.finishRetiring(actorId, reason, now);
                repository.saveAndFlush(lineage);
                for (AgentVersionEntity version :
                        versionRepository.findByAgentIdOrderByVersionAsc(lineage.getId())) {
                    version.retire(actorId, reason, now);
                    versionRepository.save(version);
                }
                Snapshot snapshot = snapshotOf(lineage);
                audit(snapshot, "RETIRED", actorId, changeReason);
                return statusResponse(snapshot);
            });
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> {
                CustomAgentEntity lineage = require(licenseCode, agentId);
                lineage.restoreActive(actorId, "Retirement aborted", clock.instant());
                repository.saveAndFlush(lineage);
            });
            throw exception;
        }
    }

    private Snapshot createLineageWithDraft(String tenant, CreateCustomAgentRequest definition,
            String actorId, String changeReason, Instant now) {
        String normalizedName = normalizeName(definition.name());
        if (repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(tenant, normalizedName)) {
            throw new DuplicateAgentNameException(definition.name());
        }
        String reason = normalizeNullable(changeReason);
        CustomAgentEntity lineage = CustomAgentEntity.newLineage(
                UUID.randomUUID().toString(), tenant, definition.name(), normalizedName,
                definition.description(), actorId, reason, now);
        AgentVersionEntity draft = AgentVersionEntity.newDraft(
                UUID.randomUUID().toString(), lineage.getId(), tenant, lineage.getDraftVersion(),
                definitionJsonMapper.write(definition), actorId, reason, now);
        try {
            repository.saveAndFlush(lineage);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateAgentNameException(definition.name());
        }
        versionRepository.saveAndFlush(draft);
        return new Snapshot(lineage, draft);
    }

    private CreateCustomAgentRequest merge(String definitionJson, JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new InvalidAgentDefinitionRequestException("Patch must be a non-empty JSON object");
        }
        ObjectNode merged = definitionJsonMapper.tree(
                definitionJsonMapper.read(definitionJson)).deepCopy();
        patch.fields().forEachRemaining(field -> {
            if (!PATCH_FIELDS.contains(field.getKey())) {
                throw new InvalidAgentDefinitionRequestException(
                        "Field is not editable: " + field.getKey());
            }
            if (field.getValue().isNull() && REQUIRED_PATCH_FIELDS.contains(field.getKey())) {
                throw new InvalidAgentDefinitionRequestException(
                        "Required field cannot be null: " + field.getKey());
            }
            merged.set(field.getKey(), field.getValue());
        });
        CreateCustomAgentRequest converted = definitionJsonMapper.convert(merged);
        return validate(normalizeDefinition(converted, converted.name().strip()));
    }

    private CreateCustomAgentRequest validate(CreateCustomAgentRequest result) {
        Set<ConstraintViolation<CreateCustomAgentRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted().collect(Collectors.joining("; "));
            throw new InvalidAgentDefinitionRequestException(detail);
        }
        return result;
    }

    private void validatePublishedDependencies(CustomAgentEntity lineage, AgentVersionEntity draft) {
        CreateCustomAgentRequest definition = definitionJsonMapper.read(draft.getDefinitionJson());
        Map<String, CustomToolEntity> tools = toolRepository
                .findByLicenseCodeAndStatusAndDeletedFalse(lineage.getLicenseCode(), ToolStatus.PUBLISHED)
                .stream().collect(Collectors.toMap(CustomToolEntity::getName, tool -> tool));
        for (String name : definition.allowedTools()) {
            CustomToolEntity tool = tools.get(name);
            if (tool == null) throw new AgentDependencyException(
                    "Allowed tool is missing or not published: " + name);
            if (tool.getType() == ToolType.CUSTOM_AGENT) {
                JsonNode configuration = readTree(tool.getDefinitionJson()).path("configuration");
                String targetId = configuration.path("agentId").asText("");
                CustomAgentEntity target = repository
                        .findByIdAndLicenseCodeAndDeletedFalse(targetId, lineage.getLicenseCode())
                        .orElseThrow(() -> new AgentDependencyException(
                                "Custom-agent tool target is missing: " + name));
                if (target.getStatus() != AgentLineageStatus.ACTIVE || !target.hasActiveVersion()) {
                    throw new AgentDependencyException(
                            "Custom-agent tool target is not published: " + name);
                }
                int requiredVersion = configuration.path("agentVersion").asInt(0);
                if (requiredVersion > 0 && requiredVersion != target.getActiveVersion()) {
                    throw new AgentDependencyException(
                            "Custom-agent tool target version does not match: " + name);
                }
            }
        }
    }

    private List<Map<String, String>> publishedDependents(String licenseCode, String agentId) {
        Set<String> toolNames = toolRepository
                .findByLicenseCodeAndStatusAndDeletedFalse(licenseCode.strip(), ToolStatus.PUBLISHED)
                .stream()
                .filter(tool -> tool.getType() == ToolType.CUSTOM_AGENT)
                .filter(tool -> agentId.equals(readTree(tool.getDefinitionJson())
                        .path("configuration").path("agentId").asText()))
                .map(CustomToolEntity::getName)
                .collect(Collectors.toSet());
        if (toolNames.isEmpty()) return List.of();
        List<CustomAgentEntity> candidates = repository
                .findByLicenseCodeAndStatusAndActiveVersionIsNotNullAndDeletedFalse(
                        licenseCode.strip(), AgentLineageStatus.ACTIVE)
                .stream().filter(candidate -> !candidate.getId().equals(agentId)).toList();
        Map<String, List<AgentVersionEntity>> versions = versionsByAgentId(
                candidates.stream().map(CustomAgentEntity::getId).toList());
        List<Map<String, String>> dependents = new ArrayList<>();
        for (CustomAgentEntity candidate : candidates) {
            AgentVersionEntity active = versions.getOrDefault(candidate.getId(), List.of()).stream()
                    .filter(version -> version.getVersion() == candidate.getActiveVersion())
                    .findFirst().orElse(null);
            if (active == null) continue;
            Set<String> allowed = definitionJsonMapper.read(active.getDefinitionJson()).allowedTools();
            if (allowed.stream().anyMatch(toolNames::contains)) {
                dependents.add(Map.of("agentId", candidate.getId(), "name", candidate.getName()));
            }
        }
        return List.copyOf(dependents);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new AgentDependencyException("Stored tool definition is invalid");
        }
    }

    private CustomAgentEntity require(String licenseCode, String agentId) {
        String id = agentId.strip();
        return repository.findByIdAndLicenseCodeAndDeletedFalse(id, licenseCode.strip())
                .orElseThrow(() -> new AgentNotFoundException(id));
    }

    private Snapshot requireSnapshot(String licenseCode, String agentId) {
        return snapshotOf(require(licenseCode, agentId));
    }

    /** Pairs an identity with the version callers should see: the serving one, else the draft. */
    private Snapshot snapshotOf(CustomAgentEntity lineage) {
        return new Snapshot(lineage, reportable(
                lineage, versionRepository.findByAgentIdOrderByVersionAsc(lineage.getId())));
    }

    private AgentVersionEntity reportable(CustomAgentEntity lineage, List<AgentVersionEntity> versions) {
        Integer preferred = lineage.hasActiveVersion()
                ? lineage.getActiveVersion() : lineage.getDraftVersion();
        Optional<AgentVersionEntity> match = versions.stream()
                .filter(version -> preferred != null && version.getVersion() == preferred)
                .findFirst();
        if (match.isPresent()) return match.get();
        return versions.stream()
                .max((left, right) -> Integer.compare(left.getVersion(), right.getVersion()))
                .orElseThrow(() -> new AgentNotFoundException(lineage.getId()));
    }

    private AgentVersionEntity requireDraft(CustomAgentEntity lineage) {
        if (!lineage.hasDraft()) {
            throw new InvalidAgentStatusTransitionException(
                    apiStatus(snapshotOf(lineage)), AgentStatus.DRAFT);
        }
        return requireVersion(lineage, lineage.getDraftVersion());
    }

    private AgentVersionEntity requireVersion(CustomAgentEntity lineage, int version) {
        return versionRepository.findByAgentIdAndVersion(lineage.getId(), version)
                .orElseThrow(() -> new AgentNotFoundException(
                        lineage.getId() + " version " + version));
    }

    private Map<String, List<AgentVersionEntity>> versionsByAgentId(List<String> agentIds) {
        if (agentIds.isEmpty()) return Map.of();
        Map<String, List<AgentVersionEntity>> grouped = new HashMap<>();
        for (AgentVersionEntity version : versionRepository.findByAgentIdIn(agentIds)) {
            grouped.computeIfAbsent(version.getAgentId(), key -> new ArrayList<>()).add(version);
        }
        return grouped;
    }

    private void audit(Snapshot snapshot, String action, String actorId, String changeReason) {
        auditRepository.save(new AgentAuditEventEntity(
                UUID.randomUUID().toString(), snapshot.lineage().getId(),
                snapshot.version() == null ? null : snapshot.version().getVersion(),
                snapshot.lineage().getLicenseCode(), action, actorId,
                normalizeNullable(changeReason), clock.instant()));
    }

    private CustomAgentResponse response(Snapshot snapshot) {
        return response(snapshot, definitionJsonMapper.read(snapshot.version().getDefinitionJson()));
    }

    private CustomAgentResponse response(Snapshot snapshot, CreateCustomAgentRequest definition) {
        CustomAgentEntity lineage = snapshot.lineage();
        return new CustomAgentResponse(lineage.getId(), lineage.getLicenseCode(), apiStatus(snapshot),
                snapshot.version().getVersion(), definition,
                lineage.getCreatedAt(), lineage.getUpdatedAt());
    }

    private AgentStatusResponse statusResponse(Snapshot snapshot) {
        CustomAgentEntity lineage = snapshot.lineage();
        return new AgentStatusResponse(lineage.getId(), lineage.getLicenseCode(), apiStatus(snapshot),
                snapshot.version().getVersion(), lineage.getUpdatedAt());
    }

    /** The API-facing status: lineage retirement wins, otherwise the reported version's state. */
    private AgentStatus apiStatus(Snapshot snapshot) {
        return switch (snapshot.lineage().getStatus()) {
            case RETIRING -> AgentStatus.RETIRING;
            case RETIRED -> AgentStatus.RETIRED;
            case ACTIVE -> switch (snapshot.version().getStatus()) {
                case DRAFT -> AgentStatus.DRAFT;
                case PUBLISHED -> AgentStatus.PUBLISHED;
                case SUPERSEDED -> AgentStatus.SUPERSEDED;
                case RETIRED -> AgentStatus.RETIRED;
            };
        };
    }

    private String normalizeName(String name) { return name.strip().toLowerCase(Locale.ROOT); }
    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private CreateCustomAgentRequest normalizeDefinition(CreateCustomAgentRequest request, String name) {
        return new CreateCustomAgentRequest(
                name, normalizeNullable(request.description()), request.role().strip(),
                request.instructions().strip(), request.rules().stream().map(String::strip).toList(),
                normalizeNullable(request.outputFormat()), nullableNode(request.outputSchema()),
                nullableNode(request.context()),
                request.examples(), request.allowedTools().stream().map(String::strip)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                nullableNode(request.humanInteractionPolicy()));
    }

    private JsonNode nullableNode(JsonNode value) {
        return value == null || value.isNull() ? null : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
