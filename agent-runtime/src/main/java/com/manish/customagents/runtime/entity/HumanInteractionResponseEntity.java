package com.manish.customagents.runtime.entity;

import com.manish.customagents.runtime.enums.HumanResponseAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "human_interaction_responses")
public class HumanInteractionResponseEntity {
    @Id @Column(length = 36, nullable = false) private String id;
    @Column(name = "batch_id", length = 36, updatable = false)
    private String batchId;
    @Column(name = "interaction_id", length = 36, nullable = false, updatable = false)
    private String interactionId;
    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false, updatable = false)
    private HumanResponseAction action;
    @Lob @Column(name = "response_json", nullable = false, columnDefinition = "LONGTEXT")
    private String responseJson;
    @Column(name = "actor_id", length = 128, nullable = false, updatable = false)
    private String actorId;
    @Lob @Column(name = "actor_roles_json", nullable = false, columnDefinition = "LONGTEXT")
    private String actorRolesJson;
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected HumanInteractionResponseEntity() {}

    public HumanInteractionResponseEntity(
            String id, String interactionId, String licenseCode, HumanResponseAction action,
            String responseJson, String actorId, String actorRolesJson,
            String idempotencyKey, Instant createdAt) {
        this(id, null, interactionId, licenseCode, action, responseJson, actorId,
                actorRolesJson, idempotencyKey, createdAt);
    }

    public HumanInteractionResponseEntity(
            String id, String batchId, String interactionId, String licenseCode,
            HumanResponseAction action, String responseJson, String actorId,
            String actorRolesJson, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.batchId = batchId;
        this.interactionId = interactionId;
        this.licenseCode = licenseCode;
        this.action = action;
        this.responseJson = responseJson;
        this.actorId = actorId;
        this.actorRolesJson = actorRolesJson;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getBatchId() { return batchId; }
    public String getInteractionId() { return interactionId; }
    public HumanResponseAction getAction() { return action; }
    public String getResponseJson() { return responseJson; }
    public String getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}
