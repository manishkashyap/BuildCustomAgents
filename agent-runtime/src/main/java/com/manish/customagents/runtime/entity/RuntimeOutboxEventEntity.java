package com.manish.customagents.runtime.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "runtime_outbox_events")
public class RuntimeOutboxEventEntity {
    @Id @Column(length = 36, nullable = false) private String id;
    @Column(name = "aggregate_id", length = 36, nullable = false) private String aggregateId;
    @Column(name = "event_type", length = 60, nullable = false) private String eventType;
    @Lob @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;
    @Column(length = 20, nullable = false) private String status;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "claimed_at") private Instant claimedAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "last_error", length = 2000) private String lastError;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected RuntimeOutboxEventEntity() {}

    public RuntimeOutboxEventEntity(
            String id, String aggregateId, String eventType, String payloadJson, Instant at) {
        this(id, aggregateId, eventType, payloadJson, at, at);
    }

    public RuntimeOutboxEventEntity(
            String id, String aggregateId, String eventType, String payloadJson,
            Instant availableAt, Instant createdAt) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.availableAt = availableAt;
        this.createdAt = createdAt;
    }

    public void claim(Instant at) { status = "CLAIMED"; claimedAt = at; attemptCount++; }
    public void complete(Instant at) { status = "PUBLISHED"; publishedAt = at; }
    public void retry(String error, Instant availableAt) {
        status = attemptCount >= 10 ? "FAILED" : "PENDING";
        lastError = error;
        this.availableAt = availableAt;
        claimedAt = null;
    }
    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayloadJson() { return payloadJson; }
    public int getAttemptCount() { return attemptCount; }
    public String getStatus() { return status; }
}
