package com.manish.customagents.credential.entity;

import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.credential.enums.CredentialStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_credentials")
public class TenantCredentialEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(name = "name", length = 128, nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false, updatable = false)
    private CredentialType type;

    @Column(name = "description", length = 1000)
    private String description;

    @Lob
    @Column(name = "secret_cipher", nullable = false, columnDefinition = "LONGTEXT")
    private String secretCipher;

    @Column(name = "key_id", length = 64, nullable = false)
    private String keyId;

    @Lob
    @Column(name = "settings_json", nullable = false, columnDefinition = "LONGTEXT")
    private String settingsJson;

    @Column(name = "allowed_hosts", length = 1000)
    private String allowedHosts;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 128, nullable = false)
    private String updatedBy;

    protected TenantCredentialEntity() {
    }

    public TenantCredentialEntity(
            String licenseCode, String name, CredentialType type, String description,
            String secretCipher, String keyId, String settingsJson, String actorId, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.licenseCode = licenseCode;
        this.name = name;
        this.type = type;
        this.description = description;
        this.secretCipher = secretCipher;
        this.keyId = keyId;
        this.settingsJson = settingsJson;
        this.status = CredentialStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Name and type are immutable. Changing either in place would silently re-point every tool that
     * references the credential, or change how the stored secret is interpreted; both are better
     * served by creating a second credential.
     */
    public void update(CredentialStatus newStatus, String newDescription, String actorId, Instant now) {
        this.status = newStatus;
        this.description = newDescription;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    /** Replaces the secret and its non-secret settings, keeping the name every tool refers to. */
    public void rotate(String newSecretCipher, String newKeyId, String newSettingsJson,
            String actorId, Instant now) {
        this.secretCipher = newSecretCipher;
        this.keyId = newKeyId;
        this.settingsJson = newSettingsJson;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getLicenseCode() { return licenseCode; }
    public String getName() { return name; }
    public CredentialType getType() { return type; }
    public String getDescription() { return description; }
    public String getKeyId() { return keyId; }
    public String getSettingsJson() { return settingsJson; }
    public String getAllowedHosts() { return allowedHosts; }
    public CredentialStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }

    /**
     * Intentionally not a getter for the plaintext. Nothing in Management ever needs to read a secret
     * back — it writes it and Runtime resolves it — so the ciphertext is the only thing exposed, and
     * even that never leaves the service.
     */
    public String getSecretCipher() { return secretCipher; }
}
