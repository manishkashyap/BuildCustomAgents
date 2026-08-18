package com.manish.customagents.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "agent_run_checkpoints")
public class AgentRunCheckpointEntity {

    @Id
    @Column(name = "run_id", length = 36, nullable = false)
    private String runId;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "next_turn_number", nullable = false)
    private int nextTurnNumber;

    @Lob @Column(name = "messages_json", nullable = false, columnDefinition = "LONGTEXT")
    private String messagesJson;

    @Lob @Column(name = "pending_work_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pendingWorkJson;

    @Lob @Column(name = "token_usage_json", nullable = false, columnDefinition = "LONGTEXT")
    private String tokenUsageJson;

    @Lob @Column(name = "execution_scope_json", nullable = false, columnDefinition = "LONGTEXT")
    private String executionScopeJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AgentRunCheckpointEntity() {}

    public AgentRunCheckpointEntity(
            String runId, int nextTurnNumber, String messagesJson, String pendingWorkJson,
            String tokenUsageJson, String executionScopeJson, Instant at) {
        this.runId = runId;
        this.version = 1;
        this.nextTurnNumber = nextTurnNumber;
        this.messagesJson = messagesJson;
        this.pendingWorkJson = pendingWorkJson;
        this.tokenUsageJson = tokenUsageJson;
        this.executionScopeJson = executionScopeJson;
        this.createdAt = at;
        this.updatedAt = at;
    }

    public void update(
            int nextTurnNumber, String messagesJson, String pendingWorkJson,
            String tokenUsageJson, String executionScopeJson, Instant at) {
        this.nextTurnNumber = nextTurnNumber;
        this.messagesJson = messagesJson;
        this.pendingWorkJson = pendingWorkJson;
        this.tokenUsageJson = tokenUsageJson;
        this.executionScopeJson = executionScopeJson;
        this.updatedAt = at;
    }

    public String getRunId() { return runId; }
    public int getNextTurnNumber() { return nextTurnNumber; }
    public String getMessagesJson() { return messagesJson; }
    public String getPendingWorkJson() { return pendingWorkJson; }
    public String getTokenUsageJson() { return tokenUsageJson; }
    public String getExecutionScopeJson() { return executionScopeJson; }
}
