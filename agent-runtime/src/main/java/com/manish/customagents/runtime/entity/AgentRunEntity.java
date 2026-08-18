package com.manish.customagents.runtime.entity;

import com.manish.customagents.runtime.enums.AgentRunStatus;
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
@Table(name = "agent_runs")
public class AgentRunEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "root_run_id", length = 36, nullable = false, updatable = false)
    private String rootRunId;

    @Column(name = "parent_run_id", length = 36, updatable = false)
    private String parentRunId;

    @Column(name = "parent_tool_invocation_id", updatable = false)
    private Long parentToolInvocationId;

    @Column(name = "license_code", length = 128, nullable = false, updatable = false)
    private String licenseCode;

    @Column(name = "requested_by", length = 128, updatable = false)
    private String requestedBy;

    @Column(name = "agent_id", length = 36, nullable = false, updatable = false)
    private String agentId;

    @Column(name = "agent_version", nullable = false, updatable = false)
    private int agentVersion;

    @Column(length = 30, nullable = false, updatable = false)
    private String provider;

    @Column(length = 150, nullable = false, updatable = false)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AgentRunStatus status;

    @Column(name = "wait_reason", length = 30)
    private String waitReason;

    @Lob
    @Column(name = "input_json", nullable = false, columnDefinition = "LONGTEXT")
    private String inputJson;

    @Lob
    @Column(name = "output_json", columnDefinition = "LONGTEXT")
    private String outputJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected AgentRunEntity() {
    }

    public AgentRunEntity(
            String id, String licenseCode, String agentId, int agentVersion,
            String provider, String model, String inputJson, Instant startedAt) {
        this(id, id, null, null, licenseCode, null, agentId, agentVersion,
                provider, model, inputJson, startedAt);
    }

    public AgentRunEntity(
            String id, String rootRunId, String parentRunId, Long parentToolInvocationId,
            String licenseCode, String requestedBy, String agentId, int agentVersion,
            String provider, String model, String inputJson, Instant startedAt) {
        this.id = id;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.parentToolInvocationId = parentToolInvocationId;
        this.licenseCode = licenseCode;
        this.requestedBy = requestedBy;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.provider = provider;
        this.model = model;
        this.status = AgentRunStatus.RUNNING;
        this.inputJson = inputJson;
        this.startedAt = startedAt;
        this.lastActivityAt = startedAt;
    }

    public void succeed(String outputJson, Instant completedAt) {
        this.status = AgentRunStatus.SUCCEEDED;
        this.outputJson = outputJson;
        this.completedAt = completedAt;
        this.lastActivityAt = completedAt;
        this.waitReason = null;
    }

    public void fail(String errorMessage, Instant completedAt) {
        this.status = AgentRunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
        this.lastActivityAt = completedAt;
        this.waitReason = null;
    }

    public void waitForHuman(String reason, Instant at) {
        this.status = AgentRunStatus.WAITING_FOR_HUMAN;
        this.waitReason = reason;
        this.lastActivityAt = at;
    }

    public void waitForChild(Instant at) {
        this.status = AgentRunStatus.WAITING_FOR_CHILD;
        this.waitReason = "CHILD_RUN";
        this.lastActivityAt = at;
    }

    public void resume(Instant at) {
        if (status != AgentRunStatus.WAITING_FOR_HUMAN
                && status != AgentRunStatus.WAITING_FOR_CHILD
                && status != AgentRunStatus.PAUSED) {
            throw new IllegalStateException("Run cannot resume from " + status);
        }
        this.status = AgentRunStatus.RUNNING;
        this.waitReason = null;
        this.lastActivityAt = at;
    }

    public void cancel(Instant at) {
        if (isTerminal()) {
            return;
        }
        this.status = AgentRunStatus.CANCELLED;
        this.completedAt = at;
        this.lastActivityAt = at;
        this.waitReason = null;
    }

    public boolean isTerminal() {
        return status == AgentRunStatus.SUCCEEDED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.EXPIRED;
    }

    public String getId() { return id; }
    public String getRootRunId() { return rootRunId; }
    public String getParentRunId() { return parentRunId; }
    public Long getParentToolInvocationId() { return parentToolInvocationId; }
    public String getLicenseCode() { return licenseCode; }
    public String getRequestedBy() { return requestedBy; }
    public String getAgentId() { return agentId; }
    public int getAgentVersion() { return agentVersion; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public AgentRunStatus getStatus() { return status; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getCompletedAt() { return completedAt; }
}
