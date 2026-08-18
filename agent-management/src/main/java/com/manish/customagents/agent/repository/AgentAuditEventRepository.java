package com.manish.customagents.agent.repository;

import com.manish.customagents.agent.entity.AgentAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAuditEventRepository extends JpaRepository<AgentAuditEventEntity, String> {}
