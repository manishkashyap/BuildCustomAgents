package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.HumanResponseBatchEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanResponseBatchRepository extends JpaRepository<HumanResponseBatchEntity, String> {
    Optional<HumanResponseBatchEntity> findByLicenseCodeAndIdempotencyKey(
            String licenseCode, String idempotencyKey);
}
