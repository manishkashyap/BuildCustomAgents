package com.manish.customagents.tool.repository;

import com.manish.customagents.tool.entity.CustomToolEntity;

import java.util.Optional;
import java.util.List;
import com.manish.customagents.tool.enums.ToolStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomToolRepository extends JpaRepository<CustomToolEntity, String> {
    boolean existsByLicenseCodeAndNormalizedNameAndDeletedFalse(String licenseCode, String normalizedName);
    Optional<CustomToolEntity> findByIdAndLicenseCodeAndDeletedFalse(String id, String licenseCode);
    List<CustomToolEntity> findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(String licenseCode);
    List<CustomToolEntity> findByLicenseCodeAndStatusAndDeletedFalse(
            String licenseCode, ToolStatus status);
}
