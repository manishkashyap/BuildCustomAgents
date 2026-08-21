package com.manish.customagents.agent.repository;

import com.manish.customagents.agent.entity.CustomAgentEntity;
import com.manish.customagents.agent.enums.AgentLineageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomAgentRepository extends JpaRepository<CustomAgentEntity, String> {

    boolean existsByLicenseCodeAndNormalizedNameAndDeletedFalse(String licenseCode, String normalizedName);

    Optional<CustomAgentEntity> findByIdAndLicenseCodeAndDeletedFalse(String id, String licenseCode);

    List<CustomAgentEntity> findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(String licenseCode);

    List<CustomAgentEntity> findByLicenseCodeAndStatusAndDeletedFalse(
            String licenseCode, AgentLineageStatus status);

    /** Agents that currently serve traffic: active identity with a version behind it. */
    List<CustomAgentEntity> findByLicenseCodeAndStatusAndActiveVersionIsNotNullAndDeletedFalse(
            String licenseCode, AgentLineageStatus status);

    List<CustomAgentEntity> findByStatusAndUpdatedAtBeforeAndDeletedFalse(
            AgentLineageStatus status, Instant updatedBefore);
}
