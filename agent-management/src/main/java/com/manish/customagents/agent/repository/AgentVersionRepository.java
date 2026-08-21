package com.manish.customagents.agent.repository;

import com.manish.customagents.agent.entity.AgentVersionEntity;
import com.manish.customagents.agent.enums.AgentVersionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentVersionRepository extends JpaRepository<AgentVersionEntity, String> {

    Optional<AgentVersionEntity> findByAgentIdAndVersion(String agentId, int version);

    List<AgentVersionEntity> findByAgentIdOrderByVersionAsc(String agentId);

    List<AgentVersionEntity> findByAgentIdAndStatus(String agentId, AgentVersionStatus status);

    List<AgentVersionEntity> findByAgentIdIn(Collection<String> agentIds);
}
