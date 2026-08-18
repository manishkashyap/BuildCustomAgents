package com.manish.customagents.agent.repository;

import com.manish.customagents.agent.entity.CustomAgentEntity;
import java.util.Optional;
import java.util.List;
import com.manish.customagents.agent.enums.AgentStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomAgentRepository extends JpaRepository<CustomAgentEntity, String> {

    boolean existsByLicenseCodeAndNormalizedNameAndDeletedFalse(String licenseCode, String normalizedName);

    Optional<CustomAgentEntity> findByIdAndLicenseCodeAndDeletedFalse(String id, String licenseCode);

    List<CustomAgentEntity> findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(String licenseCode);

    List<CustomAgentEntity> findByLicenseCodeAndStatusAndDeletedFalse(
            String licenseCode, AgentStatus status);

    List<CustomAgentEntity> findByStatusAndUpdatedAtBeforeAndDeletedFalse(
            AgentStatus status, Instant updatedBefore);
}
