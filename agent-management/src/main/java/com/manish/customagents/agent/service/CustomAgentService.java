package com.manish.customagents.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.agent.entity.AgentAuditEventEntity;
import com.manish.customagents.agent.entity.AgentCopyRequestEntity;
import com.manish.customagents.agent.entity.CustomAgentEntity;
import com.manish.customagents.agent.enums.AgentStatus;
import com.manish.customagents.agent.model.AgentStatusResponse;
import com.manish.customagents.agent.model.CopyCustomAgentRequest;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.agent.model.CustomAgentResponse;
import com.manish.customagents.agent.model.RetirementEligibilityResponse;
import com.manish.customagents.agent.repository.AgentAuditEventRepository;
import com.manish.customagents.agent.repository.AgentCopyRequestRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Duration;

@Service
public class CustomAgentService {
    private static final Set<String> PATCH_FIELDS = Set.of(
            "name", "description", "role", "instructions", "rules", "outputFormat",
            "outputSchema", "context", "examples", "allowedTools", "humanInteractionPolicy");
    private static final Set<String> REQUIRED_PATCH_FIELDS = Set.of(
            "name", "role", "instructions", "rules", "examples", "allowedTools");

    private final CustomAgentRepository repository;
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

    @Transactional
    public CustomAgentResponse create(String licenseCode, CreateCustomAgentRequest request,
            String actorId, String changeReason) {
        String tenant = licenseCode.strip();
        String name = request.name().strip();
        CreateCustomAgentRequest definition = normalizeDefinition(request, name);
        CustomAgentEntity entity = createDraft(tenant, definition, actorId, changeReason, clock.instant());
        audit(entity, "CREATED", actorId, changeReason);
        return response(entity);
    }

    @Transactional(readOnly = true)
    public List<CustomAgentResponse> list(String licenseCode) {
        return repository.findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(
                        licenseCode.strip()).stream()
                .map(this::response)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomAgentResponse get(String licenseCode, String agentId) {
        return response(require(licenseCode, agentId));
    }

    @Transactional
    public CustomAgentResponse updateDraft(String licenseCode, String agentId, JsonNode patch,
            String actorId, String changeReason) {
        CustomAgentEntity entity = require(licenseCode, agentId);
        if (entity.getStatus() != AgentStatus.DRAFT) {
            throw new InvalidAgentStatusTransitionException(entity.getStatus(), AgentStatus.DRAFT);
        }
        CreateCustomAgentRequest updated = merge(entity, patch);
        String oldNormalizedName = entity.getNormalizedName();
        String normalizedName = normalizeName(updated.name());
        if (!oldNormalizedName.equals(normalizedName)
                && repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                        entity.getLicenseCode(), normalizedName)) {
            throw new DuplicateAgentNameException(updated.name());
        }
        Instant now = clock.instant();
        entity.updateDraft(updated.name(), normalizedName, updated.description(),
                definitionJsonMapper.write(updated), actorId, normalizeNullable(changeReason), now);
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateAgentNameException(updated.name());
        }
        audit(entity, "UPDATED", actorId, changeReason);
        return response(entity, updated);
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
            return response(require(tenant, replay.getCopiedAgentId()));
        }

        CustomAgentEntity source = require(tenant, sourceAgentId);
        if (source.getStatus() != AgentStatus.PUBLISHED && source.getStatus() != AgentStatus.RETIRED) {
            throw new InvalidAgentStatusTransitionException(source.getStatus(), AgentStatus.DRAFT);
        }
        CreateCustomAgentRequest sourceDefinition = definitionJsonMapper.read(source.getDefinitionJson());
        CreateCustomAgentRequest copiedDefinition = normalizeDefinition(new CreateCustomAgentRequest(
                request.name(), sourceDefinition.description(), sourceDefinition.role(),
                sourceDefinition.instructions(), sourceDefinition.rules(), sourceDefinition.outputFormat(),
                sourceDefinition.outputSchema(), sourceDefinition.context(), sourceDefinition.examples(),
                sourceDefinition.allowedTools(), sourceDefinition.humanInteractionPolicy()), request.name().strip());
        Instant now = clock.instant();
        CustomAgentEntity copied = createDraft(
                tenant, copiedDefinition, actorId, changeReason, now);
        copyRepository.saveAndFlush(new AgentCopyRequestEntity(
                idempotencyKey, tenant, hash, copied.getId(), actorId, now));
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
        AgentStatus current = transactions.execute(status -> require(licenseCode, agentId).getStatus());
        throw new InvalidAgentStatusTransitionException(current, requestedStatus);
    }

    @Scheduled(fixedDelayString = "${agents.retirement.recovery-interval-ms:60000}")
    @Transactional
    public void recoverStaleRetirements() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(5));
        for (CustomAgentEntity entity : repository
                .findByStatusAndUpdatedAtBeforeAndDeletedFalse(AgentStatus.RETIRING, cutoff)) {
            entity.restorePublished("system", "Recovered interrupted retirement", clock.instant());
            repository.save(entity);
            audit(entity, "RETIREMENT_RECOVERED", "system", "Recovered interrupted retirement");
        }
    }

    private AgentStatusResponse publish(String licenseCode, String agentId,
            String actorId, String changeReason) {
        CustomAgentEntity entity = require(licenseCode, agentId);
        if (entity.getStatus() == AgentStatus.PUBLISHED) return statusResponse(entity);
        if (entity.getStatus() != AgentStatus.DRAFT) {
            throw new InvalidAgentStatusTransitionException(entity.getStatus(), AgentStatus.PUBLISHED);
        }
        validatePublishedDependencies(entity);
        entity.publish(actorId, normalizeNullable(changeReason), clock.instant());
        repository.saveAndFlush(entity);
        audit(entity, "PUBLISHED", actorId, changeReason);
        return statusResponse(entity);
    }

    private AgentStatusResponse retire(String licenseCode, String agentId,
            String actorId, String changeReason) {
        AgentStatusResponse marked = transactions.execute(status -> {
            CustomAgentEntity entity = require(licenseCode, agentId);
            if (entity.getStatus() == AgentStatus.RETIRED) return statusResponse(entity);
            if (entity.getStatus() != AgentStatus.PUBLISHED) {
                throw new InvalidAgentStatusTransitionException(entity.getStatus(), AgentStatus.RETIRED);
            }
            entity.beginRetiring(actorId, normalizeNullable(changeReason), clock.instant());
            repository.saveAndFlush(entity);
            return statusResponse(entity);
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
                CustomAgentEntity entity = require(licenseCode, agentId);
                entity.finishRetiring(actorId, normalizeNullable(changeReason), clock.instant());
                repository.saveAndFlush(entity);
                audit(entity, "RETIRED", actorId, changeReason);
                return statusResponse(entity);
            });
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> {
                CustomAgentEntity entity = require(licenseCode, agentId);
                entity.restorePublished(actorId, "Retirement aborted", clock.instant());
                repository.saveAndFlush(entity);
            });
            throw exception;
        }
    }

    private CustomAgentEntity createDraft(String tenant, CreateCustomAgentRequest definition,
            String actorId, String changeReason, Instant now) {
        String normalizedName = normalizeName(definition.name());
        if (repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(tenant, normalizedName)) {
            throw new DuplicateAgentNameException(definition.name());
        }
        CustomAgentEntity entity = new CustomAgentEntity(
                UUID.randomUUID().toString(), tenant, definition.name(), normalizedName,
                definition.description(), definitionJsonMapper.write(definition), AgentStatus.DRAFT,
                1, false, now, now, actorId, actorId, normalizeNullable(changeReason));
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateAgentNameException(definition.name());
        }
    }

    private CreateCustomAgentRequest merge(CustomAgentEntity entity, JsonNode patch) {
        if (patch == null || !patch.isObject() || patch.isEmpty()) {
            throw new InvalidAgentDefinitionRequestException("Patch must be a non-empty JSON object");
        }
        ObjectNode merged = definitionJsonMapper.tree(
                definitionJsonMapper.read(entity.getDefinitionJson())).deepCopy();
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
        CreateCustomAgentRequest result = normalizeDefinition(converted, converted.name().strip());
        Set<ConstraintViolation<CreateCustomAgentRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted().collect(Collectors.joining("; "));
            throw new InvalidAgentDefinitionRequestException(detail);
        }
        return result;
    }

    private void validatePublishedDependencies(CustomAgentEntity entity) {
        CreateCustomAgentRequest definition = definitionJsonMapper.read(entity.getDefinitionJson());
        Map<String, CustomToolEntity> tools = toolRepository
                .findByLicenseCodeAndStatusAndDeletedFalse(entity.getLicenseCode(), ToolStatus.PUBLISHED)
                .stream().collect(Collectors.toMap(CustomToolEntity::getName, tool -> tool));
        for (String name : definition.allowedTools()) {
            CustomToolEntity tool = tools.get(name);
            if (tool == null) throw new AgentDependencyException(
                    "Allowed tool is missing or not published: " + name);
            if (tool.getType() == ToolType.CUSTOM_AGENT) {
                JsonNode configuration = readTree(tool.getDefinitionJson()).path("configuration");
                String targetId = configuration.path("agentId").asText("");
                CustomAgentEntity target = repository
                        .findByIdAndLicenseCodeAndDeletedFalse(targetId, entity.getLicenseCode())
                        .orElseThrow(() -> new AgentDependencyException(
                                "Custom-agent tool target is missing: " + name));
                if (target.getStatus() != AgentStatus.PUBLISHED) {
                    throw new AgentDependencyException(
                            "Custom-agent tool target is not published: " + name);
                }
                int requiredVersion = configuration.path("agentVersion").asInt(0);
                if (requiredVersion > 0 && requiredVersion != target.getVersion()) {
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
        List<Map<String, String>> dependents = new ArrayList<>();
        for (CustomAgentEntity candidate : repository.findByLicenseCodeAndStatusAndDeletedFalse(
                licenseCode.strip(), AgentStatus.PUBLISHED)) {
            if (candidate.getId().equals(agentId)) continue;
            Set<String> allowed = definitionJsonMapper.read(candidate.getDefinitionJson()).allowedTools();
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

    private void audit(CustomAgentEntity entity, String action, String actorId, String changeReason) {
        auditRepository.save(new AgentAuditEventEntity(
                UUID.randomUUID().toString(), entity.getId(), entity.getLicenseCode(), action,
                actorId, normalizeNullable(changeReason), clock.instant()));
    }

    private CustomAgentResponse response(CustomAgentEntity entity) {
        return response(entity, definitionJsonMapper.read(entity.getDefinitionJson()));
    }

    private CustomAgentResponse response(CustomAgentEntity entity, CreateCustomAgentRequest definition) {
        return new CustomAgentResponse(entity.getId(), entity.getLicenseCode(), entity.getStatus(),
                entity.getVersion(), definition, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private AgentStatusResponse statusResponse(CustomAgentEntity entity) {
        return new AgentStatusResponse(entity.getId(), entity.getLicenseCode(), entity.getStatus(),
                entity.getVersion(), entity.getUpdatedAt());
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
