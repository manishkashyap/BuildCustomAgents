package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.HumanInteractionRequestEntity;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HumanInteractionRequestRepository
        extends JpaRepository<HumanInteractionRequestEntity, String> {
    Optional<HumanInteractionRequestEntity> findByIdAndLicenseCode(String id, String licenseCode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from HumanInteractionRequestEntity request "
            + "where request.id = :id and request.licenseCode = :licenseCode")
    Optional<HumanInteractionRequestEntity> findForUpdate(
            @Param("id") String id, @Param("licenseCode") String licenseCode);
    List<HumanInteractionRequestEntity> findByRootRunIdAndStatusOrderByCreatedAtAsc(
            String rootRunId, HumanInteractionStatus status);
    List<HumanInteractionRequestEntity> findByLicenseCodeAndStatusOrderByCreatedAtAsc(
            String licenseCode, HumanInteractionStatus status);
    long countByRootRunId(String rootRunId);
    boolean existsByRunIdAndStatus(String runId, HumanInteractionStatus status);
}
