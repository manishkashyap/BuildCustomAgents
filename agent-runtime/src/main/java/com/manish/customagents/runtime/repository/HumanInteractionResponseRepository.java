package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.HumanInteractionResponseEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HumanInteractionResponseRepository
        extends JpaRepository<HumanInteractionResponseEntity, String> {
    Optional<HumanInteractionResponseEntity> findByInteractionId(String interactionId);
    Optional<HumanInteractionResponseEntity> findByLicenseCodeAndIdempotencyKey(
            String licenseCode, String idempotencyKey);
}
