package com.manish.customagents.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_run_turns")
public class AgentRunTurnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", length = 36, nullable = false, updatable = false)
    private String runId;

    @Column(name = "turn_number", nullable = false, updatable = false)
    private int turnNumber;

    @Lob
    @Column(name = "request_json", nullable = false, columnDefinition = "LONGTEXT")
    private String requestJson;

    @Lob
    @Column(name = "response_json", nullable = false, columnDefinition = "LONGTEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentRunTurnEntity() {
    }

    public AgentRunTurnEntity(
            String runId, int turnNumber, String requestJson, String responseJson, Instant createdAt) {
        this.runId = runId;
        this.turnNumber = turnNumber;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public int getTurnNumber() { return turnNumber; }
    public String getRequestJson() { return requestJson; }
    public String getResponseJson() { return responseJson; }
    public Instant getCreatedAt() { return createdAt; }
}
