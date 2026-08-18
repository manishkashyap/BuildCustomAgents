package com.manish.customagents.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "human_response_batches")
public class HumanResponseBatchEntity {
    @Id @Column(length = 36, nullable = false) private String id;
    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;
    @Column(name = "root_run_id", length = 36, nullable = false, updatable = false)
    private String rootRunId;
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "request_sha256", length = 64, nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    private String requestSha256;
    @Lob @Column(name = "response_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String responseJson;
    @Column(name = "actor_id", length = 128, nullable = false, updatable = false)
    private String actorId;
    @Lob @Column(name = "actor_roles_json", nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String actorRolesJson;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected HumanResponseBatchEntity() {}

    public HumanResponseBatchEntity(
            String id, String licenseCode, String rootRunId, String idempotencyKey,
            String requestSha256, String responseJson, String actorId,
            String actorRolesJson, Instant createdAt) {
        this.id = id;
        this.licenseCode = licenseCode;
        this.rootRunId = rootRunId;
        this.idempotencyKey = idempotencyKey;
        this.requestSha256 = requestSha256;
        this.responseJson = responseJson;
        this.actorId = actorId;
        this.actorRolesJson = actorRolesJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getRootRunId() { return rootRunId; }
    public String getRequestSha256() { return requestSha256; }
    public String getResponseJson() { return responseJson; }
    public String getActorId() { return actorId; }
}
