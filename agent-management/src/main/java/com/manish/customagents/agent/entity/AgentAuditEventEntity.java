package com.manish.customagents.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_audit_events")
public class AgentAuditEventEntity {
    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false, updatable = false,
            columnDefinition = "CHAR(36)")
    private String agentId;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(length = 30, nullable = false, updatable = false)
    private String action;

    @Column(name = "actor_id", length = 128, nullable = false, updatable = false)
    private String actorId;

    @Column(name = "change_reason", length = 1000, updatable = false)
    private String changeReason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AgentAuditEventEntity() {}

    public AgentAuditEventEntity(String id, String agentId, String licenseCode, String action,
            String actorId, String changeReason, Instant occurredAt) {
        this.id = id;
        this.agentId = agentId;
        this.licenseCode = licenseCode;
        this.action = action;
        this.actorId = actorId;
        this.changeReason = changeReason;
        this.occurredAt = occurredAt;
    }
}
