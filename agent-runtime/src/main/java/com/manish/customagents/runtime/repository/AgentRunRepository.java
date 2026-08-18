package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.AgentRunEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.manish.customagents.runtime.enums.AgentRunStatus;
import java.util.Set;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {
    Optional<AgentRunEntity> findByIdAndLicenseCode(String id, String licenseCode);
    List<AgentRunEntity> findByRootRunId(String rootRunId);
    long countByRootRunId(String rootRunId);

    @Query("""
            select r.status as status, count(r) as total
            from AgentRunEntity r
            where r.licenseCode = :licenseCode and r.agentId = :agentId and r.status in :statuses
            group by r.status
            """)
    List<StatusCount> countActiveByAgent(
            @Param("licenseCode") String licenseCode,
            @Param("agentId") String agentId,
            @Param("statuses") Set<AgentRunStatus> statuses);

    interface StatusCount {
        AgentRunStatus getStatus();
        long getTotal();
    }
}
