package com.manish.customagents.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_copy_requests")
@IdClass(AgentCopyRequestEntity.Key.class)
public class AgentCopyRequestEntity {
    @Id @Column(name = "idempotency_key", length = 200, nullable = false, updatable = false)
    private String idempotencyKey;
    @Id @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;
    @Column(name = "request_sha256", length = 64, nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    private String requestSha256;
    @Column(name = "copied_agent_id", length = 36, nullable = false, updatable = false,
            columnDefinition = "CHAR(36)")
    private String copiedAgentId;
    @Column(name = "created_by", length = 128, nullable = false, updatable = false)
    private String createdBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentCopyRequestEntity() {}

    public AgentCopyRequestEntity(String idempotencyKey, String licenseCode, String requestSha256,
            String copiedAgentId, String createdBy, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.licenseCode = licenseCode;
        this.requestSha256 = requestSha256;
        this.copiedAgentId = copiedAgentId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getRequestSha256() { return requestSha256; }
    public String getCopiedAgentId() { return copiedAgentId; }

    public static class Key implements Serializable {
        private String idempotencyKey;
        private String licenseCode;

        public Key() {}
        public Key(String idempotencyKey, String licenseCode) {
            this.idempotencyKey = idempotencyKey;
            this.licenseCode = licenseCode;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(idempotencyKey, key.idempotencyKey)
                    && Objects.equals(licenseCode, key.licenseCode);
        }

        @Override public int hashCode() { return Objects.hash(idempotencyKey, licenseCode); }
    }
}
