package com.manish.customagents.agent.entity;

import com.manish.customagents.agent.enums.AgentLineageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

/**
 * The identity of an agent, independent of any single authored revision.
 *
 * <p>Definitions live in {@link AgentVersionEntity}. This row records which version currently
 * serves traffic and which version is editable, so a published version and a new draft can
 * coexist. The two single-valued slots make "at most one serving and one editable version"
 * a structural guarantee rather than an application-level rule.
 */
@Entity
@Table(
        name = "custom_agents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_custom_agents_tenant_name_deleted",
                columnNames = {"license_code", "normalized_name", "deleted"}))
public class CustomAgentEntity {

    private static final int FIRST_VERSION = 1;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentLineageStatus status;

    @Column(name = "active_version")
    private Integer activeVersion;

    @Column(name = "draft_version")
    private Integer draftVersion;

    @Column(name = "next_version", nullable = false)
    private int nextVersion;

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
            AgentLineageStatus status,
            Integer activeVersion,
            Integer draftVersion,
            int nextVersion,
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
        this.status = status;
        this.activeVersion = activeVersion;
        this.draftVersion = draftVersion;
        this.nextVersion = nextVersion;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
        this.updatedBy = updatedBy;
        this.changeReason = changeReason;
    }

    /**
     * Creates a new agent identity with no versions yet. The caller allocates the first
     * draft with {@link #allocateVersion}, so the version counter is the single source of
     * version numbers rather than one of two places that know the sequence starts at 1.
     */
    public static CustomAgentEntity newLineage(String id, String licenseCode, String name,
            String normalizedName, String description, String actorId, String changeReason,
            Instant now) {
        return new CustomAgentEntity(id, licenseCode, name, normalizedName, description,
                AgentLineageStatus.ACTIVE, null, null, FIRST_VERSION, false, now, now,
                actorId, actorId, changeReason);
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

    public AgentLineageStatus getStatus() {
        return status;
    }

    public Integer getActiveVersion() {
        return activeVersion;
    }

    public Integer getDraftVersion() {
        return draftVersion;
    }

    public int getNextVersion() {
        return nextVersion;
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

    public boolean hasDraft() {
        return draftVersion != null;
    }

    public boolean hasActiveVersion() {
        return activeVersion != null;
    }

    /**
     * Reserves the next version number. Numbers are never reused, so discarding a draft
     * does not let a later draft reuse its number.
     */
    public int allocateVersion(String actorId, String changeReason, Instant at) {
        int allocated = nextVersion;
        nextVersion = allocated + 1;
        draftVersion = allocated;
        touch(actorId, changeReason, at);
        return allocated;
    }

    /** Applies draft-level edits that belong to the identity rather than the definition. */
    public void renameDraft(String name, String normalizedName, String description,
            String actorId, String changeReason, Instant at) {
        if (status != AgentLineageStatus.ACTIVE) {
            throw new IllegalStateException("Only active agents can be edited; current status is " + status);
        }
        this.name = Objects.requireNonNull(name);
        this.normalizedName = Objects.requireNonNull(normalizedName);
        this.description = description;
        touch(actorId, changeReason, at);
    }

    /** Points the identity at a newly published version and clears the draft slot. */
    public void promote(int version, String actorId, String changeReason, Instant at) {
        if (status != AgentLineageStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active agents can be published; current status is " + status);
        }
        this.activeVersion = version;
        if (draftVersion != null && draftVersion.intValue() == version) {
            this.draftVersion = null;
        }
        touch(actorId, changeReason, at);
    }

    public void beginRetiring(String actorId, String changeReason, Instant at) {
        if (status != AgentLineageStatus.ACTIVE || activeVersion == null) {
            throw new IllegalStateException(
                    "Only published agents can be retired; current status is " + status);
        }
        status = AgentLineageStatus.RETIRING;
        touch(actorId, changeReason, at);
    }

    public void finishRetiring(String actorId, String changeReason, Instant at) {
        if (status != AgentLineageStatus.RETIRING) throw new IllegalStateException("Agent is not retiring");
        status = AgentLineageStatus.RETIRED;
        touch(actorId, changeReason, at);
    }

    public void restoreActive(String actorId, String changeReason, Instant at) {
        if (status != AgentLineageStatus.RETIRING) return;
        status = AgentLineageStatus.ACTIVE;
        touch(actorId, changeReason, at);
    }

    private void touch(String actorId, String changeReason, Instant at) {
        this.updatedBy = Objects.requireNonNull(actorId, "actorId must not be null");
        this.changeReason = changeReason;
        this.updatedAt = Objects.requireNonNull(at, "updatedAt must not be null");
    }
}
