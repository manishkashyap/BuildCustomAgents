package com.manish.customagents.credential.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A record of a credential being created, changed, or attached to a tool.
 *
 * <p>Credential management is open to anyone who can author a tool, and credentials carry no host
 * scope, so the tenant egress allowlist is the only barrier between a credential and an arbitrary
 * server. This table does not prevent misuse — it makes it reconstructable afterwards.
 */
@Entity
@Table(name = "credential_binding_events")
public class CredentialBindingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(name = "credential_name", length = 128, nullable = false, updatable = false)
    private String credentialName;

    @Column(name = "tool_id", length = 36, updatable = false, columnDefinition = "CHAR(36)")
    private String toolId;

    @Column(name = "tool_name", length = 150, updatable = false)
    private String toolName;

    @Column(name = "target_host", length = 255, updatable = false)
    private String targetHost;

    @Column(name = "action", length = 30, nullable = false, updatable = false)
    private String action;

    @Column(name = "actor_id", length = 128, nullable = false, updatable = false)
    private String actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected CredentialBindingEventEntity() {
    }

    public CredentialBindingEventEntity(
            String licenseCode, String credentialName, String toolId, String toolName,
            String targetHost, String action, String actorId, Instant occurredAt) {
        this.licenseCode = licenseCode;
        this.credentialName = credentialName;
        this.toolId = toolId;
        this.toolName = toolName;
        this.targetHost = targetHost;
        this.action = action;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public String getLicenseCode() { return licenseCode; }
    public String getCredentialName() { return credentialName; }
    public String getToolId() { return toolId; }
    public String getToolName() { return toolName; }
    public String getTargetHost() { return targetHost; }
    public String getAction() { return action; }
    public String getActorId() { return actorId; }
    public Instant getOccurredAt() { return occurredAt; }
}
