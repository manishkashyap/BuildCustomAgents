package com.manish.customagents.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.manish.customagents.budget.DefinitionBudgetValidator;
import com.manish.customagents.egress.service.ToolEgressValidator;
import com.manish.customagents.error.DuplicateToolNameException;
import com.manish.customagents.error.InvalidToolStatusTransitionException;
import com.manish.customagents.error.ToolNotFoundException;
import com.manish.customagents.tool.entity.CustomToolEntity;
import com.manish.customagents.tool.enums.ToolStatus;
import com.manish.customagents.tool.model.CreateToolRequest;
import com.manish.customagents.tool.model.ToolResponse;
import com.manish.customagents.tool.model.ToolStatusResponse;
import com.manish.customagents.tool.repository.CustomToolRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomToolService {

    private final CustomToolRepository repository;
    private final ToolDefinitionJsonMapper definitionJsonMapper;
    private final DefinitionBudgetValidator budgetValidator;
    private final ToolEgressValidator egressValidator;
    private final Clock clock;

    public CustomToolService(
            CustomToolRepository repository,
            ToolDefinitionJsonMapper definitionJsonMapper,
            DefinitionBudgetValidator budgetValidator,
            ToolEgressValidator egressValidator,
            Clock clock) {
        this.repository = repository;
        this.definitionJsonMapper = definitionJsonMapper;
        this.budgetValidator = budgetValidator;
        this.egressValidator = egressValidator;
        this.clock = clock;
    }

    @Transactional
    public ToolResponse create(String licenseCode, CreateToolRequest request) {
        String tenant = licenseCode.strip();
        CreateToolRequest normalized = normalize(request);
        String name = normalized.name();
        String normalizedName = normalizeName(name);

        if (repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(tenant, normalizedName)) {
            throw new DuplicateToolNameException(name);
        }

        Instant now = clock.instant();
        CustomToolEntity entity = new CustomToolEntity(
                UUID.randomUUID().toString(), tenant, name, normalizedName,
                normalized.description(), normalized.type(), definitionJsonMapper.write(normalized),
                ToolStatus.DRAFT, 1, false, now, now);
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateToolNameException(name);
        }
        return response(entity, normalized);
    }

    @Transactional(readOnly = true)
    public List<ToolResponse> list(String licenseCode) {
        return repository.findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(
                        licenseCode.strip()).stream()
                .map(entity -> response(
                        entity, definitionJsonMapper.read(entity.getDefinitionJson())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ToolResponse get(String licenseCode, String toolId) {
        CustomToolEntity entity = require(licenseCode, toolId);
        return response(entity, definitionJsonMapper.read(entity.getDefinitionJson()));
    }

    @Transactional
    public ToolResponse updateDraft(
            String licenseCode, String toolId, CreateToolRequest request) {
        CustomToolEntity entity = require(licenseCode, toolId);
        if (entity.getStatus() != ToolStatus.DRAFT) {
            throw new InvalidToolStatusTransitionException(
                    entity.getStatus(), ToolStatus.DRAFT);
        }
        CreateToolRequest normalized = normalize(request);
        String normalizedName = normalizeName(normalized.name());
        if (!entity.getNormalizedName().equals(normalizedName)
                && repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                        entity.getLicenseCode(), normalizedName)) {
            throw new DuplicateToolNameException(normalized.name());
        }
        entity.updateDraft(
                normalized.name(), normalizedName, normalized.description(), normalized.type(),
                definitionJsonMapper.write(normalized), clock.instant());
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateToolNameException(normalized.name());
        }
        return response(entity, normalized);
    }

    @Transactional
    public ToolStatusResponse updateStatus(
            String licenseCode, String toolId, ToolStatus requestedStatus) {
        CustomToolEntity entity = require(licenseCode, toolId);
        if (requestedStatus != ToolStatus.PUBLISHED) {
            throw new InvalidToolStatusTransitionException(entity.getStatus(), requestedStatus);
        }
        if (entity.getStatus() == ToolStatus.PUBLISHED) {
            return statusResponse(entity);
        }
        if (entity.getStatus() != ToolStatus.DRAFT) {
            throw new InvalidToolStatusTransitionException(entity.getStatus(), requestedStatus);
        }
        // Checked here rather than on create so a draft can be saved while it is still being
        // written, and so a budget change never strands an already-published tool.
        CreateToolRequest definition = definitionJsonMapper.read(entity.getDefinitionJson());
        budgetValidator.checkTool(definition);
        // Same reasoning as the budget check: validated on publish, not on save, so a draft can be
        // written against a host an administrator has not allowed yet.
        egressValidator.check(licenseCode, definition.type(), definition.configuration());
        entity.publish(clock.instant());
        repository.saveAndFlush(entity);
        return statusResponse(entity);
    }

    private ToolResponse response(CustomToolEntity entity, CreateToolRequest definition) {
        return new ToolResponse(
                entity.getId(), entity.getLicenseCode(), entity.getStatus(), entity.getVersion(),
                definition, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ToolStatusResponse statusResponse(CustomToolEntity entity) {
        return new ToolStatusResponse(
                entity.getId(), entity.getLicenseCode(), entity.getStatus(),
                entity.getVersion(), entity.getUpdatedAt());
    }

    private CustomToolEntity require(String licenseCode, String toolId) {
        String id = toolId.strip();
        return repository.findByIdAndLicenseCodeAndDeletedFalse(id, licenseCode.strip())
                .orElseThrow(() -> new ToolNotFoundException(id));
    }

    private CreateToolRequest normalize(CreateToolRequest request) {
        return new CreateToolRequest(
                request.name().strip(), request.description().strip(), request.type(),
                request.inputSchema(), request.outputSchema(), request.configuration(),
                objectOrEmpty(request.executionPolicy()));
    }

    private String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private JsonNode objectOrEmpty(JsonNode value) {
        return value == null ? JsonNodeFactory.instance.objectNode() : value;
    }
}
