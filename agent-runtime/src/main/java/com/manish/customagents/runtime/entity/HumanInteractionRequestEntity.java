package com.manish.customagents.runtime.entity;

import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.enums.HumanResponseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "human_interaction_requests")
public class HumanInteractionRequestEntity {
    @Id @Column(length = 36, nullable = false)
    private String id;
    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;
    @Column(name = "root_run_id", length = 36, nullable = false, updatable = false)
    private String rootRunId;
    @Column(name = "run_id", length = 36, nullable = false, updatable = false)
    private String runId;
    @Column(name = "tool_invocation_id", updatable = false)
    private Long toolInvocationId;
    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false, updatable = false)
    private HumanInteractionType type;
    @Enumerated(EnumType.STRING) @Column(length = 20, nullable = false)
    private HumanInteractionStatus status;
    @Column(length = 40) private String category;
    @Column(length = 4000) private String question;
    @Column(length = 4000) private String reason;
    @Enumerated(EnumType.STRING) @Column(name = "response_type", length = 30, nullable = false)
    private HumanResponseType responseType;
    @Lob @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;
    @Enumerated(EnumType.STRING) @Column(name = "audience_type", length = 30, nullable = false)
    private HumanAudienceType audienceType;
    @Lob @Column(name = "audience_values_json", nullable = false, columnDefinition = "LONGTEXT")
    private String audienceValuesJson;
    @Column(name = "assigned_user_id", length = 128)
    private String assignedUserId;
    @Column(name = "subject_sha256", length = 64, nullable = false, updatable = false,
            columnDefinition = "CHAR(64)")
    private String subjectSha256;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "lock_version", nullable = false) private long lockVersion;

    protected HumanInteractionRequestEntity() {}

    public HumanInteractionRequestEntity(
            String id, String licenseCode, String rootRunId, String runId, Long toolInvocationId,
            HumanInteractionType type, String category, String question, String reason,
            HumanResponseType responseType, String requestJson, HumanAudienceType audienceType,
            String audienceValuesJson, String assignedUserId, String subjectSha256,
            Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.licenseCode = licenseCode;
        this.rootRunId = rootRunId;
        this.runId = runId;
        this.toolInvocationId = toolInvocationId;
        this.type = type;
        this.status = HumanInteractionStatus.PENDING;
        this.category = category;
        this.question = question;
        this.reason = reason;
        this.responseType = responseType;
        this.requestJson = requestJson;
        this.audienceType = audienceType;
        this.audienceValuesJson = audienceValuesJson;
        this.assignedUserId = assignedUserId;
        this.subjectSha256 = subjectSha256;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void resolve(HumanResponseAction action, Instant at) {
        if (status != HumanInteractionStatus.PENDING) {
            throw new IllegalStateException("Interaction is already " + status);
        }
        status = switch (action) {
            case ANSWER -> HumanInteractionStatus.ANSWERED;
            case APPROVE -> HumanInteractionStatus.APPROVED;
            case REJECT -> HumanInteractionStatus.REJECTED;
        };
        resolvedAt = at;
        updatedAt = at;
    }

    public void cancel(Instant at) {
        if (status == HumanInteractionStatus.PENDING) {
            status = HumanInteractionStatus.CANCELLED;
            resolvedAt = at;
            updatedAt = at;
        }
    }

    public String getId() { return id; }
    public String getLicenseCode() { return licenseCode; }
    public String getRootRunId() { return rootRunId; }
    public String getRunId() { return runId; }
    public Long getToolInvocationId() { return toolInvocationId; }
    public HumanInteractionType getType() { return type; }
    public HumanInteractionStatus getStatus() { return status; }
    public String getCategory() { return category; }
    public String getQuestion() { return question; }
    public String getReason() { return reason; }
    public HumanResponseType getResponseType() { return responseType; }
    public String getRequestJson() { return requestJson; }
    public HumanAudienceType getAudienceType() { return audienceType; }
    public String getAudienceValuesJson() { return audienceValuesJson; }
    public String getAssignedUserId() { return assignedUserId; }
    public String getSubjectSha256() { return subjectSha256; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
