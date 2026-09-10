package com.manish.customagents.credential.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.CredentialCipher;
import com.manish.customagents.contracts.ToolAuthRules;
import com.manish.customagents.credential.config.CredentialEncryptionProperties;
import com.manish.customagents.credential.entity.CredentialBindingEventEntity;
import com.manish.customagents.credential.entity.TenantCredentialEntity;
import com.manish.customagents.credential.enums.CredentialStatus;
import com.manish.customagents.credential.model.CreateCredentialRequest;
import com.manish.customagents.credential.model.CredentialResponse;
import com.manish.customagents.credential.model.UpdateCredentialRequest;
import com.manish.customagents.credential.repository.CredentialBindingEventRepository;
import com.manish.customagents.credential.repository.TenantCredentialRepository;
import com.manish.customagents.error.CredentialInUseException;
import com.manish.customagents.error.CredentialNotFoundException;
import com.manish.customagents.error.DuplicateCredentialNameException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns a tenant's credentials. Writes secrets encrypted; never reads one back out. */
@Service
public class TenantCredentialService {

    private final TenantCredentialRepository repository;
    private final CredentialBindingEventRepository auditRepository;
    private final CredentialSettingsValidator settingsValidator;
    private final CredentialReferenceScanner referenceScanner;
    private final CredentialCipher cipher;
    private final CredentialEncryptionProperties encryptionProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TenantCredentialService(
            TenantCredentialRepository repository,
            CredentialBindingEventRepository auditRepository,
            CredentialSettingsValidator settingsValidator,
            CredentialReferenceScanner referenceScanner,
            CredentialCipher cipher,
            CredentialEncryptionProperties encryptionProperties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.settingsValidator = settingsValidator;
        this.referenceScanner = referenceScanner;
        this.cipher = cipher;
        this.encryptionProperties = encryptionProperties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CredentialResponse> list(String licenseCode) {
        return repository.findByLicenseCodeOrderByNameAsc(licenseCode).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CredentialResponse get(String licenseCode, String id) {
        return toResponse(require(licenseCode, id));
    }

    @Transactional
    public CredentialResponse create(String licenseCode, CreateCredentialRequest request, String actorId) {
        String name = ToolAuthRules.normalizeName(request.name());
        repository.findByLicenseCodeAndName(licenseCode, name).ifPresent(existing -> {
            throw new DuplicateCredentialNameException(name);
        });
        settingsValidator.validate(request.type(), request.settings());

        TenantCredentialEntity entity = new TenantCredentialEntity(
                licenseCode, name, request.type(), blankToNull(request.description()),
                cipher.encrypt(request.secret()), encryptionProperties.getKeyId(),
                writeSettings(request.settings()), actorId, clock.instant());
        TenantCredentialEntity saved = repository.saveAndFlush(entity);
        audit(licenseCode, name, null, null, null, "CREDENTIAL_CREATED", actorId);
        return toResponse(saved);
    }

    @Transactional
    public CredentialResponse update(
            String licenseCode, String id, UpdateCredentialRequest request, String actorId) {
        TenantCredentialEntity entity = require(licenseCode, id);
        if (request.secret() != null || request.settings() != null) {
            JsonNode settings = request.settings() != null
                    ? request.settings()
                    : readSettings(entity.getSettingsJson());
            settingsValidator.validate(entity.getType(), settings);
            // Rotating settings without a new secret keeps the existing ciphertext.
            String secretCipher = request.secret() != null
                    ? cipher.encrypt(request.secret())
                    : entity.getSecretCipher();
            String keyId = request.secret() != null
                    ? encryptionProperties.getKeyId()
                    : entity.getKeyId();
            entity.rotate(secretCipher, keyId, writeSettings(settings), actorId, clock.instant());
        }
        entity.update(
                request.status() != null ? request.status() : entity.getStatus(),
                request.description() != null ? blankToNull(request.description()) : entity.getDescription(),
                actorId, clock.instant());
        TenantCredentialEntity saved = repository.saveAndFlush(entity);
        audit(licenseCode, entity.getName(), null, null, null, "CREDENTIAL_UPDATED", actorId);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String licenseCode, String id, String actorId) {
        TenantCredentialEntity entity = require(licenseCode, id);
        // Deleting a referenced credential would break the tool at run time rather than here.
        List<String> users = referenceScanner.toolsReferencing(licenseCode, entity.getName());
        if (!users.isEmpty()) {
            throw new CredentialInUseException(entity.getName(), users);
        }
        repository.delete(entity);
        audit(licenseCode, entity.getName(), null, null, null, "CREDENTIAL_DELETED", actorId);
    }

    /** Used by tool validation: the credential must exist and be active before a tool can publish. */
    @Transactional(readOnly = true)
    public TenantCredentialEntity requireActiveByName(String licenseCode, String name) {
        TenantCredentialEntity entity = repository
                .findByLicenseCodeAndName(licenseCode, ToolAuthRules.normalizeName(name))
                .orElseThrow(() -> new CredentialNotFoundException(name));
        if (entity.getStatus() != CredentialStatus.ACTIVE) {
            throw new CredentialNotFoundException(name + " (status " + entity.getStatus() + ")");
        }
        return entity;
    }

    @Transactional
    public void recordBinding(
            String licenseCode, String credentialName, String toolId, String toolName,
            String targetHost, String action, String actorId) {
        audit(licenseCode, credentialName, toolId, toolName, targetHost, action, actorId);
    }

    private void audit(String licenseCode, String credentialName, String toolId, String toolName,
            String targetHost, String action, String actorId) {
        auditRepository.save(new CredentialBindingEventEntity(
                licenseCode, credentialName, toolId, toolName, targetHost, action,
                actorId, clock.instant()));
    }

    private TenantCredentialEntity require(String licenseCode, String id) {
        return repository.findByIdAndLicenseCode(id, licenseCode)
                .orElseThrow(() -> new CredentialNotFoundException(id));
    }

    private String writeSettings(JsonNode settings) {
        try {
            return objectMapper.writeValueAsString(
                    settings == null ? objectMapper.createObjectNode() : settings);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialise credential settings", exception);
        }
    }

    private JsonNode readSettings(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored credential settings are invalid", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private CredentialResponse toResponse(TenantCredentialEntity entity) {
        return new CredentialResponse(
                entity.getId(), entity.getLicenseCode(), entity.getName(), entity.getType(),
                entity.getDescription(), readSettings(entity.getSettingsJson()), entity.getStatus(),
                entity.getKeyId(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getCreatedBy(), entity.getUpdatedBy());
    }
}
