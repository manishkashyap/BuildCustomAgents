package com.manish.customagents.egress.entity;

import com.manish.customagents.egress.enums.EgressHostStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_egress_hosts")
public class TenantEgressHostEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(name = "host_pattern", length = 255, nullable = false, updatable = false)
    private String hostPattern;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private EgressHostStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected TenantEgressHostEntity() {
    }

    public TenantEgressHostEntity(
            String licenseCode, String hostPattern, String description, String actorId, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.licenseCode = licenseCode;
        this.hostPattern = hostPattern;
        this.description = description;
        this.status = EgressHostStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * The pattern itself is immutable: editing it in place would silently re-point every tool that
     * relies on it. Callers disable the row and register a new one instead.
     */
    public void update(EgressHostStatus newStatus, String newDescription, String actorId, Instant now) {
        this.status = newStatus;
        this.description = newDescription;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getLicenseCode() { return licenseCode; }
    public String getHostPattern() { return hostPattern; }
    public String getDescription() { return description; }
    public EgressHostStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
}
