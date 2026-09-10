package com.manish.customagents.credential.repository;

import com.manish.customagents.credential.entity.TenantCredentialEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantCredentialRepository extends JpaRepository<TenantCredentialEntity, String> {
    List<TenantCredentialEntity> findByLicenseCodeOrderByNameAsc(String licenseCode);
    Optional<TenantCredentialEntity> findByIdAndLicenseCode(String id, String licenseCode);
    Optional<TenantCredentialEntity> findByLicenseCodeAndName(String licenseCode, String name);
}
