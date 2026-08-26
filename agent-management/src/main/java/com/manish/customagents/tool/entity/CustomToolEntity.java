package com.manish.customagents.tool.entity;

import com.manish.customagents.tool.enums.ToolStatus;
import com.manish.customagents.contracts.ToolType;
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
        name = "custom_tools",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_custom_tools_tenant_name_deleted",
                columnNames = {"license_code", "normalized_name", "deleted"}))
public class CustomToolEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(length = 150, nullable = false)
    private String name;

    @Column(name = "normalized_name", length = 150, nullable = false)
    private String normalizedName;

    @Column(length = 1000, nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ToolType type;

    @Lob
    @Column(name = "definition_json", nullable = false, columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private ToolStatus status;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomToolEntity() {
    }

    public CustomToolEntity(
            String id, String licenseCode, String name, String normalizedName,
            String description, ToolType type, String definitionJson, ToolStatus status,
            int version, boolean deleted, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.licenseCode = licenseCode;
        this.name = name;
        this.normalizedName = normalizedName;
        this.description = description;
        this.type = type;
        this.definitionJson = definitionJson;
        this.status = status;
        this.version = version;
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void publish(Instant publishedAt) {
        if (status != ToolStatus.DRAFT) {
            throw new IllegalStateException("Only draft tools can be published; current status is " + status);
        }
        status = ToolStatus.PUBLISHED;
        updatedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }

    public void updateDraft(
            String name, String normalizedName, String description, ToolType type,
            String definitionJson, Instant updatedAt) {
        if (status != ToolStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only draft tools can be edited; current status is " + status);
        }
        this.name = Objects.requireNonNull(name);
        this.normalizedName = Objects.requireNonNull(normalizedName);
        this.description = Objects.requireNonNull(description);
        this.type = Objects.requireNonNull(type);
        this.definitionJson = Objects.requireNonNull(definitionJson);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public String getId() { return id; }
    public String getLicenseCode() { return licenseCode; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getDescription() { return description; }
    public ToolType getType() { return type; }
    public String getDefinitionJson() { return definitionJson; }
    public ToolStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public boolean isDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
