ALTER TABLE custom_agents
    DROP CHECK chk_custom_agents_status,
    ADD CONSTRAINT chk_custom_agents_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRING', 'RETIRED')),
    ADD COLUMN created_by VARCHAR(128) NOT NULL DEFAULT 'legacy',
    ADD COLUMN updated_by VARCHAR(128) NOT NULL DEFAULT 'legacy',
    ADD COLUMN change_reason VARCHAR(1000) NULL;

CREATE TABLE agent_copy_requests (
    idempotency_key VARCHAR(200) NOT NULL,
    license_code   VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64)     NOT NULL,
    copied_agent_id CHAR(36)    NOT NULL,
    created_by     VARCHAR(128) NOT NULL,
    created_at     TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_copy_requests PRIMARY KEY (license_code, idempotency_key),
    CONSTRAINT fk_agent_copy_requests_agent
        FOREIGN KEY (copied_agent_id) REFERENCES custom_agents (id)
);

CREATE TABLE agent_audit_events (
    id            CHAR(36)      NOT NULL,
    agent_id      CHAR(36)      NOT NULL,
    license_code  VARCHAR(128)  NOT NULL,
    action        VARCHAR(30)   NOT NULL,
    actor_id      VARCHAR(128)  NOT NULL,
    change_reason VARCHAR(1000) NULL,
    occurred_at   TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_agent_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_agent_audit_events_agent
        FOREIGN KEY (agent_id) REFERENCES custom_agents (id)
);

CREATE INDEX idx_agent_audit_agent_time
    ON agent_audit_events (agent_id, occurred_at);
