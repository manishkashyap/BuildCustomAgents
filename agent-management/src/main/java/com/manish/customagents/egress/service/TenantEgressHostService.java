package com.manish.customagents.egress.service;

import com.manish.customagents.contracts.EgressHostRules;
import com.manish.customagents.egress.entity.TenantEgressHostEntity;
import com.manish.customagents.egress.enums.EgressHostStatus;
import com.manish.customagents.egress.model.CreateEgressHostRequest;
import com.manish.customagents.egress.model.EgressHostResponse;
import com.manish.customagents.egress.model.UpdateEgressHostRequest;
import com.manish.customagents.egress.repository.TenantEgressHostRepository;
import com.manish.customagents.error.DuplicateEgressHostException;
import com.manish.customagents.error.EgressHostNotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns a tenant's HTTP egress allowlist: which hosts this tenant's tools are permitted to reach. */
@Service
public class TenantEgressHostService {

    private final TenantEgressHostRepository repository;
    private final Clock clock;

    public TenantEgressHostService(TenantEgressHostRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EgressHostResponse> list(String licenseCode) {
        return repository.findByLicenseCodeOrderByHostPatternAsc(licenseCode).stream()
                .map(TenantEgressHostService::toResponse)
                .toList();
    }

    @Transactional
    public EgressHostResponse create(String licenseCode, CreateEgressHostRequest request, String actorId) {
        String pattern = EgressHostRules.normalize(request.hostPattern());
        repository.findByLicenseCodeAndHostPattern(licenseCode, pattern).ifPresent(existing -> {
            throw new DuplicateEgressHostException(pattern);
        });
        TenantEgressHostEntity entity = new TenantEgressHostEntity(
                licenseCode, pattern, blankToNull(request.description()), actorId, clock.instant());
        return toResponse(repository.saveAndFlush(entity));
    }

    @Transactional
    public EgressHostResponse update(
            String licenseCode, String id, UpdateEgressHostRequest request, String actorId) {
        TenantEgressHostEntity entity = repository.findByIdAndLicenseCode(id, licenseCode)
                .orElseThrow(() -> new EgressHostNotFoundException(id));
        entity.update(request.status(), blankToNull(request.description()), actorId, clock.instant());
        return toResponse(repository.saveAndFlush(entity));
    }

    @Transactional
    public void delete(String licenseCode, String id) {
        TenantEgressHostEntity entity = repository.findByIdAndLicenseCode(id, licenseCode)
                .orElseThrow(() -> new EgressHostNotFoundException(id));
        repository.delete(entity);
    }

    /** The active patterns for a tenant, used by publish-time validation. */
    @Transactional(readOnly = true)
    public List<String> activePatterns(String licenseCode) {
        return repository.findByLicenseCodeAndStatus(licenseCode, EgressHostStatus.ACTIVE).stream()
                .map(TenantEgressHostEntity::getHostPattern)
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static EgressHostResponse toResponse(TenantEgressHostEntity entity) {
        return new EgressHostResponse(
                entity.getId(), entity.getLicenseCode(), entity.getHostPattern(),
                entity.getDescription(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getCreatedBy(), entity.getUpdatedBy());
    }
}
