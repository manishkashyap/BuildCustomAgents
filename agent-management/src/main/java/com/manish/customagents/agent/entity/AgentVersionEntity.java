package com.manish.customagents.agent.entity;

import com.manish.customagents.agent.enums.AgentVersionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

/**
 * One authored revision of an agent. Editable while {@code DRAFT}; immutable once published.
 *
 * <p>A version that a newer release takes over becomes {@code SUPERSEDED}, not {@code RETIRED},
 * so it stays a valid rollback target and can still serve runs that started against it.
 */
@Entity
@Table(
        name = "agent_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_versions_agent_version",
                columnNames = {"agent_id", "version"}))
public class AgentVersionEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "agent_id", length = 36, nullable = false, updatable = false,
            columnDefinition = "CHAR(36)")
    private String agentId;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(nullable = false, updatable = false)
    private int version;

    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentVersionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    @Column(name = "change_reason", length = 1000)
    private String changeReason;

    protected AgentVersionEntity() {
    }

    public AgentVersionEntity(
            String id,
            String agentId,
            String licenseCode,
            int version,
            String definitionJson,
            AgentVersionStatus status,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy,
            String changeReason) {
        this.id = id;
        this.agentId = agentId;
        this.licenseCode = licenseCode;
        this.version = version;
        this.definitionJson = definitionJson;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.changeReason = changeReason;
    }

    public static AgentVersionEntity newDraft(String id, String agentId, String licenseCode,
            int version, String definitionJson, String actorId, String changeReason, Instant now) {
        return new AgentVersionEntity(id, agentId, licenseCode, version, definitionJson,
                AgentVersionStatus.DRAFT, now, now, actorId, actorId, changeReason);
    }

    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public String getLicenseCode() { return licenseCode; }
    public int getVersion() { return version; }
    public String getDefinitionJson() { return definitionJson; }
    public AgentVersionStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public String getChangeReason() { return changeReason; }

    public void updateDefinition(String definitionJson, String actorId, String changeReason,
            Instant updatedAt) {
        if (status != AgentVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft versions can be edited; current status is " + status);
        }
        this.definitionJson = Objects.requireNonNull(definitionJson);
        touch(actorId, changeReason, updatedAt);
    }

    public void publish(String actorId, String changeReason, Instant at) {
        if (status != AgentVersionStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft versions can be published; current status is " + status);
        }
        status = AgentVersionStatus.PUBLISHED;
        touch(actorId, changeReason, at);
    }

    /** Steps aside for a newer release while remaining a rollback target. */
    public void supersede(String actorId, String changeReason, Instant at) {
        if (status != AgentVersionStatus.PUBLISHED) {
            throw new IllegalStateException(
                    "Only published versions can be superseded; current status is " + status);
        }
        status = AgentVersionStatus.SUPERSEDED;
        touch(actorId, changeReason, at);
    }

    /**
     * Retires a version that has served traffic. Drafts are not retirable: a retired identity
     * already blocks publishing, so a draft is left intact rather than silently destroyed.
     */
    public void retire(String actorId, String changeReason, Instant at) {
        if (status != AgentVersionStatus.PUBLISHED && status != AgentVersionStatus.SUPERSEDED) {
            throw new IllegalStateException(
                    "Only published or superseded versions can be retired; current status is " + status);
        }
        status = AgentVersionStatus.RETIRED;
        touch(actorId, changeReason, at);
    }

    public boolean isRetirable() {
        return status == AgentVersionStatus.PUBLISHED || status == AgentVersionStatus.SUPERSEDED;
    }

    private void touch(String actorId, String changeReason, Instant at) {
        this.updatedBy = Objects.requireNonNull(actorId, "actorId must not be null");
        this.changeReason = changeReason;
        this.updatedAt = Objects.requireNonNull(at, "updatedAt must not be null");
    }
}
