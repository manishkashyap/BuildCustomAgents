package com.manish.customagents.agent.entity;

import com.manish.customagents.agent.enums.AgentStatus;
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

@Entity
@Table(
        name = "custom_agents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_custom_agents_tenant_name_deleted",
                columnNames = {"license_code", "normalized_name", "deleted"}))
public class CustomAgentEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(name = "normalized_name", length = 120, nullable = false)
    private String normalizedName;

    @Column(length = 1000)
    private String description;

    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentStatus status;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean deleted;

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

    protected CustomAgentEntity() {
    }

    public CustomAgentEntity(
            String id,
            String licenseCode,
            String name,
            String normalizedName,
            String description,
            String definitionJson,
            AgentStatus status,
            int version,
            boolean deleted,
            Instant createdAt,
            Instant updatedAt) {
        this(id, licenseCode, name, normalizedName, description, definitionJson, status,
                version, deleted, createdAt, updatedAt, "system", "system", null);
    }

    public CustomAgentEntity(
            String id,
            String licenseCode,
            String name,
            String normalizedName,
            String description,
            String definitionJson,
            AgentStatus status,
            int version,
            boolean deleted,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy,
            String changeReason) {
        this.id = id;
        this.licenseCode = licenseCode;
        this.name = name;
        this.normalizedName = normalizedName;
        this.description = description;
        this.definitionJson = definitionJson;
        this.status = status;
        this.version = version;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.changeReason = changeReason;
    }

    public String getId() {
        return id;
    }

    public String getLicenseCode() {
        return licenseCode;
    }

    public String getName() {
        return name;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getDescription() {
        return description;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public String getChangeReason() { return changeReason; }

    public void updateDraft(
            String name, String normalizedName, String description, String definitionJson,
            String actorId, String changeReason, Instant updatedAt) {
        if (status != AgentStatus.DRAFT) {
            throw new IllegalStateException("Only draft agents can be edited; current status is " + status);
        }
        this.name = Objects.requireNonNull(name);
        this.normalizedName = Objects.requireNonNull(normalizedName);
        this.description = description;
        this.definitionJson = Objects.requireNonNull(definitionJson);
        touch(actorId, changeReason, updatedAt);
    }

    public void publish(String actorId, String changeReason, Instant publishedAt) {
        if (status != AgentStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft agents can be published; current status is " + status);
        }
        status = AgentStatus.PUBLISHED;
        touch(actorId, changeReason, publishedAt);
    }

    public void beginRetiring(String actorId, String changeReason, Instant at) {
        if (status != AgentStatus.PUBLISHED) {
            throw new IllegalStateException("Only published agents can be retired; current status is " + status);
        }
        status = AgentStatus.RETIRING;
        touch(actorId, changeReason, at);
    }

    public void finishRetiring(String actorId, String changeReason, Instant at) {
        if (status != AgentStatus.RETIRING) throw new IllegalStateException("Agent is not retiring");
        status = AgentStatus.RETIRED;
        touch(actorId, changeReason, at);
    }

    public void restorePublished(String actorId, String changeReason, Instant at) {
        if (status != AgentStatus.RETIRING) return;
        status = AgentStatus.PUBLISHED;
        touch(actorId, changeReason, at);
    }

    private void touch(String actorId, String changeReason, Instant at) {
        this.updatedBy = Objects.requireNonNull(actorId, "actorId must not be null");
        this.changeReason = changeReason;
        this.updatedAt = Objects.requireNonNull(at, "updatedAt must not be null");
    }
}
