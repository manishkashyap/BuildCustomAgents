package com.manish.customagents.runtime.repository;

import com.manish.customagents.runtime.entity.AgentRunCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunCheckpointRepository
        extends JpaRepository<AgentRunCheckpointEntity, String> {
}
