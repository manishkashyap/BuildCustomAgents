package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.AgentToolInvocationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentToolInvocationRepository extends JpaRepository<AgentToolInvocationEntity, Long> {
    Optional<AgentToolInvocationEntity> findByRunIdAndToolCallId(String runId, String toolCallId);
    Optional<AgentToolInvocationEntity> findByChildRunId(String childRunId);
}
