package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface RuntimeOutboxEventRepository
        extends JpaRepository<RuntimeOutboxEventEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RuntimeOutboxEventEntity> findTop20ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc(
            String status, Instant availableAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RuntimeOutboxEventEntity> findTop20ByStatusAndClaimedAtBeforeOrderByClaimedAtAsc(
            String status, Instant claimedBefore);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from RuntimeOutboxEventEntity event where event.id = :id")
    Optional<RuntimeOutboxEventEntity> findForUpdate(@Param("id") String id);
}
