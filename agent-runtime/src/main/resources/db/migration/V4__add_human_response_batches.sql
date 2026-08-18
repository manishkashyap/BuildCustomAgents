CREATE TABLE human_response_batches (
    id               VARCHAR(36)  NOT NULL,
    license_code     VARCHAR(128) NOT NULL,
    root_run_id      VARCHAR(36)  NOT NULL,
    idempotency_key  VARCHAR(128) NOT NULL,
    request_sha256   CHAR(64)     NOT NULL,
    response_json    LONGTEXT     NOT NULL,
    actor_id         VARCHAR(128) NOT NULL,
    actor_roles_json LONGTEXT     NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_human_response_batches PRIMARY KEY (id),
    CONSTRAINT uk_human_response_batch_idempotency UNIQUE (license_code, idempotency_key),
    CONSTRAINT fk_human_response_batch_root
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id)
);

CREATE INDEX idx_human_response_batch_root
    ON human_response_batches (license_code, root_run_id, created_at);

ALTER TABLE human_interaction_responses
    ADD COLUMN batch_id VARCHAR(36) NULL AFTER id,
    ADD CONSTRAINT fk_human_response_batch
        FOREIGN KEY (batch_id) REFERENCES human_response_batches (id);

CREATE INDEX idx_human_response_batch
    ON human_interaction_responses (batch_id, created_at);
