package com.manish.customagents.runtime.entity;

import com.manish.customagents.runtime.enums.ToolInvocationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "agent_tool_invocations")
public class AgentToolInvocationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", length = 36, nullable = false, updatable = false)
    private String runId;

    @Column(name = "turn_number", nullable = false, updatable = false)
    private int turnNumber;

    @Column(name = "tool_call_id", length = 150, nullable = false, updatable = false)
    private String toolCallId;

    @Column(name = "tool_name", length = 150, nullable = false, updatable = false)
    private String toolName;

    @Column(name = "tool_id", length = 36, updatable = false)
    private String toolId;

    @Column(name = "tool_version", updatable = false)
    private Integer toolVersion;

    @Column(name = "tool_type", length = 30, updatable = false)
    private String toolType;

    @Lob
    @Column(name = "arguments_json", nullable = false, columnDefinition = "LONGTEXT")
    private String argumentsJson;

    @Column(name = "arguments_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String argumentsSha256;

    @Lob
    @Column(name = "approval_policy_json", columnDefinition = "LONGTEXT")
    private String approvalPolicyJson;

    @Column(name = "child_run_id", length = 36)
    private String childRunId;

    @Lob
    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Lob
    @Column(name = "result_metadata_json", columnDefinition = "LONGTEXT")
    private String resultMetadataJson;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ToolInvocationStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected AgentToolInvocationEntity() {
    }

    public AgentToolInvocationEntity(
            String runId, int turnNumber, String toolCallId, String toolName,
            String toolId, Integer toolVersion, String toolType,
            String argumentsJson, String resultJson, ToolInvocationStatus status,
            String errorMessage, long durationMs, Instant createdAt) {
        this.runId = runId;
        this.turnNumber = turnNumber;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolId = toolId;
        this.toolVersion = toolVersion;
        this.toolType = toolType;
        this.argumentsJson = argumentsJson;
        this.resultJson = resultJson;
        this.status = status;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
        this.startedAt = createdAt;
        this.completedAt = createdAt;
        this.updatedAt = createdAt;
    }

    public AgentToolInvocationEntity(
            String runId, int turnNumber, String toolCallId, String toolName,
            String toolId, int toolVersion, String toolType, String argumentsJson,
            String argumentsSha256, String approvalPolicyJson, Instant createdAt) {
        this.runId = runId;
        this.turnNumber = turnNumber;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolId = toolId;
        this.toolVersion = toolVersion;
        this.toolType = toolType;
        this.argumentsJson = argumentsJson;
        this.argumentsSha256 = argumentsSha256;
        this.approvalPolicyJson = approvalPolicyJson;
        this.status = ToolInvocationStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void waitForApproval(Instant at) {
        status = ToolInvocationStatus.WAITING_FOR_APPROVAL;
        updatedAt = at;
    }

    public void approve(Instant at) {
        requireStatus(ToolInvocationStatus.WAITING_FOR_APPROVAL);
        status = ToolInvocationStatus.APPROVED;
        updatedAt = at;
    }

    public void start(Instant at) {
        if (status != ToolInvocationStatus.PENDING && status != ToolInvocationStatus.APPROVED) {
            throw new IllegalStateException("Invocation cannot start from " + status);
        }
        status = ToolInvocationStatus.RUNNING;
        startedAt = at;
        updatedAt = at;
    }

    public void waitForHuman(Instant at) {
        status = ToolInvocationStatus.WAITING_FOR_HUMAN;
        updatedAt = at;
    }

    public void waitForChild(String childRunId, Instant at) {
        status = ToolInvocationStatus.WAITING_FOR_CHILD;
        this.childRunId = childRunId;
        updatedAt = at;
    }

    public void succeed(String resultJson, String metadataJson, long durationMs, Instant at) {
        status = ToolInvocationStatus.SUCCEEDED;
        this.resultJson = resultJson;
        this.resultMetadataJson = metadataJson;
        this.durationMs = durationMs;
        this.completedAt = at;
        this.updatedAt = at;
    }

    public void fail(String errorMessage, String resultJson, long durationMs, Instant at) {
        status = ToolInvocationStatus.FAILED;
        this.errorMessage = errorMessage;
        this.resultJson = resultJson;
        this.durationMs = durationMs;
        this.completedAt = at;
        this.updatedAt = at;
    }

    public void reject(String resultJson, Instant at) {
        requireStatus(ToolInvocationStatus.WAITING_FOR_APPROVAL);
        status = ToolInvocationStatus.REJECTED;
        this.resultJson = resultJson;
        this.completedAt = at;
        this.updatedAt = at;
    }

    private void requireStatus(ToolInvocationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected invocation status " + expected + " but was " + status);
        }
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public int getTurnNumber() { return turnNumber; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public String getToolId() { return toolId; }
    public Integer getToolVersion() { return toolVersion; }
    public String getToolType() { return toolType; }
    public String getArgumentsJson() { return argumentsJson; }
    public String getArgumentsSha256() { return argumentsSha256; }
    public String getApprovalPolicyJson() { return approvalPolicyJson; }
    public String getChildRunId() { return childRunId; }
    public String getResultJson() { return resultJson; }
    public ToolInvocationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
    public Long getDurationMs() { return durationMs; }
}
