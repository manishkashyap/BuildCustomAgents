package com.manish.customagents.agent.repository;

import com.manish.customagents.agent.entity.AgentCopyRequestEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentCopyRequestRepository
        extends JpaRepository<AgentCopyRequestEntity, AgentCopyRequestEntity.Key> {
    Optional<AgentCopyRequestEntity> findByLicenseCodeAndIdempotencyKey(
            String licenseCode, String idempotencyKey);
}
