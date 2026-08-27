package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.AgentRunTurnEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunTurnRepository extends JpaRepository<AgentRunTurnEntity, Long> {
    List<AgentRunTurnEntity> findByRunIdInOrderByRunIdAscTurnNumberAsc(Collection<String> runIds);
}
