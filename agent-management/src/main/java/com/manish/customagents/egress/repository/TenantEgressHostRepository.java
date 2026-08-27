package com.manish.customagents.egress.repository;

import com.manish.customagents.egress.entity.TenantEgressHostEntity;
import com.manish.customagents.egress.enums.EgressHostStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantEgressHostRepository extends JpaRepository<TenantEgressHostEntity, String> {
    List<TenantEgressHostEntity> findByLicenseCodeOrderByHostPatternAsc(String licenseCode);
    List<TenantEgressHostEntity> findByLicenseCodeAndStatus(String licenseCode, EgressHostStatus status);
    Optional<TenantEgressHostEntity> findByIdAndLicenseCode(String id, String licenseCode);
    Optional<TenantEgressHostEntity> findByLicenseCodeAndHostPattern(String licenseCode, String hostPattern);
}
