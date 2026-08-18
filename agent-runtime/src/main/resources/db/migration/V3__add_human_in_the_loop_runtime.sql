ALTER TABLE agent_runs
    DROP CHECK chk_agent_runs_status;

ALTER TABLE agent_runs
    MODIFY COLUMN status VARCHAR(30) NOT NULL,
    ADD COLUMN root_run_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN parent_run_id VARCHAR(36) NULL AFTER root_run_id,
    ADD COLUMN parent_tool_invocation_id BIGINT NULL AFTER parent_run_id,
    ADD COLUMN requested_by VARCHAR(128) NULL AFTER license_code,
    ADD COLUMN wait_reason VARCHAR(30) NULL AFTER status,
    ADD COLUMN last_activity_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

UPDATE agent_runs
SET root_run_id = id,
    last_activity_at = COALESCE(completed_at, started_at)
WHERE root_run_id IS NULL;

ALTER TABLE agent_runs
    MODIFY COLUMN root_run_id VARCHAR(36) NOT NULL,
    ADD CONSTRAINT chk_agent_runs_status
        CHECK (status IN ('RUNNING', 'WAITING_FOR_HUMAN', 'WAITING_FOR_CHILD', 'PAUSED',
                          'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    ADD CONSTRAINT chk_agent_runs_wait_reason
        CHECK (wait_reason IS NULL OR wait_reason IN
               ('CLARIFICATION', 'TOOL_APPROVAL', 'CHILD_RUN', 'OPERATOR_PAUSE')),
    ADD CONSTRAINT fk_agent_runs_root
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    ADD CONSTRAINT fk_agent_runs_parent
        FOREIGN KEY (parent_run_id) REFERENCES agent_runs (id);

CREATE INDEX idx_agent_runs_root_status
    ON agent_runs (license_code, root_run_id, status);
CREATE INDEX idx_agent_runs_parent
    ON agent_runs (parent_run_id, status);

ALTER TABLE agent_tool_invocations
    DROP CHECK chk_agent_tool_status;

ALTER TABLE agent_tool_invocations
    MODIFY COLUMN status VARCHAR(30) NOT NULL,
    MODIFY COLUMN duration_ms BIGINT NULL,
    ADD COLUMN arguments_sha256 CHAR(64) NULL AFTER arguments_json,
    ADD COLUMN approval_policy_json LONGTEXT NULL AFTER arguments_sha256,
    ADD COLUMN child_run_id VARCHAR(36) NULL AFTER approval_policy_json,
    ADD COLUMN result_metadata_json LONGTEXT NULL AFTER result_json,
    ADD COLUMN started_at TIMESTAMP(6) NULL AFTER created_at,
    ADD COLUMN completed_at TIMESTAMP(6) NULL AFTER started_at,
    ADD COLUMN updated_at TIMESTAMP(6) NULL AFTER completed_at,
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_agent_tool_status
        CHECK (status IN ('PENDING', 'WAITING_FOR_APPROVAL', 'APPROVED', 'RUNNING',
                          'WAITING_FOR_HUMAN', 'WAITING_FOR_CHILD', 'SUCCEEDED', 'FAILED',
                          'REJECTED', 'CANCELLED', 'UNKNOWN_OUTCOME')),
    ADD CONSTRAINT fk_agent_tool_child_run
        FOREIGN KEY (child_run_id) REFERENCES agent_runs (id);

CREATE INDEX idx_agent_tool_status
    ON agent_tool_invocations (run_id, status);
CREATE INDEX idx_agent_tool_child_run
    ON agent_tool_invocations (child_run_id);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_run_parent_invocation
        FOREIGN KEY (parent_tool_invocation_id) REFERENCES agent_tool_invocations (id);

CREATE TABLE agent_run_checkpoints (
    run_id               VARCHAR(36)  NOT NULL,
    version              BIGINT       NOT NULL,
    next_turn_number     INT          NOT NULL,
    messages_json        LONGTEXT     NOT NULL,
    pending_work_json    LONGTEXT     NOT NULL,
    token_usage_json     LONGTEXT     NOT NULL,
    execution_scope_json LONGTEXT     NOT NULL,
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_run_checkpoints PRIMARY KEY (run_id),
    CONSTRAINT fk_agent_checkpoint_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT chk_agent_checkpoint_version CHECK (version > 0),
    CONSTRAINT chk_agent_checkpoint_turn CHECK (next_turn_number > 0)
);

CREATE TABLE agent_run_steps (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(36)  NOT NULL,
    sequence_number INT          NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    subject_type    VARCHAR(30)  NULL,
    subject_id      VARCHAR(150) NULL,
    payload_json    LONGTEXT     NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    completed_at    TIMESTAMP(6) NULL,
    CONSTRAINT pk_agent_run_steps PRIMARY KEY (id),
    CONSTRAINT uk_agent_run_step_sequence UNIQUE (run_id, sequence_number),
    CONSTRAINT fk_agent_run_step_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT chk_agent_run_step_type
        CHECK (type IN ('LLM_TURN', 'TOOL_CALL', 'CHILD_RUN', 'HUMAN_INPUT', 'SYSTEM')),
    CONSTRAINT chk_agent_run_step_status
        CHECK (status IN ('STARTED', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE TABLE human_interaction_requests (
    id                   VARCHAR(36)   NOT NULL,
    license_code         VARCHAR(128)  NOT NULL,
    root_run_id          VARCHAR(36)   NOT NULL,
    run_id               VARCHAR(36)   NOT NULL,
    tool_invocation_id   BIGINT        NULL,
    type                 VARCHAR(30)   NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    category             VARCHAR(40)   NULL,
    question             VARCHAR(4000) NULL,
    reason               VARCHAR(4000) NULL,
    response_type        VARCHAR(30)   NOT NULL,
    request_json         LONGTEXT      NOT NULL,
    audience_type        VARCHAR(30)   NOT NULL,
    audience_values_json LONGTEXT      NOT NULL,
    assigned_user_id     VARCHAR(128)  NULL,
    subject_sha256       CHAR(64)      NOT NULL,
    expires_at           TIMESTAMP(6)  NOT NULL,
    resolved_at          TIMESTAMP(6)  NULL,
    created_at           TIMESTAMP(6)  NOT NULL,
    updated_at           TIMESTAMP(6)  NOT NULL,
    lock_version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_human_interaction_requests PRIMARY KEY (id),
    CONSTRAINT fk_human_interaction_root_run
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_human_interaction_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_human_interaction_invocation
        FOREIGN KEY (tool_invocation_id) REFERENCES agent_tool_invocations (id),
    CONSTRAINT chk_human_interaction_type
        CHECK (type IN ('CLARIFICATION', 'TOOL_APPROVAL')),
    CONSTRAINT chk_human_interaction_status
        CHECK (status IN ('PENDING', 'ANSWERED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_human_interaction_response_type
        CHECK (response_type IN ('FREE_TEXT', 'BOOLEAN', 'SINGLE_SELECT', 'MULTI_SELECT',
                                 'NUMBER', 'DATE', 'JSON', 'FILE', 'APPROVAL')),
    CONSTRAINT chk_human_interaction_audience
        CHECK (audience_type IN ('CALLER_AGENT', 'RUN_REQUESTER', 'ROLE', 'GROUP'))
);

CREATE INDEX idx_human_interaction_inbox
    ON human_interaction_requests (license_code, status, assigned_user_id, created_at);
CREATE INDEX idx_human_interaction_root
    ON human_interaction_requests (root_run_id, status, created_at);
CREATE INDEX idx_human_interaction_expiry
    ON human_interaction_requests (status, expires_at);
CREATE UNIQUE INDEX uk_human_interaction_tool_type
    ON human_interaction_requests (tool_invocation_id, type);

CREATE TABLE human_interaction_responses (
    id                 VARCHAR(36)   NOT NULL,
    interaction_id     VARCHAR(36)   NOT NULL,
    license_code       VARCHAR(128)  NOT NULL,
    action             VARCHAR(20)   NOT NULL,
    response_json      LONGTEXT      NOT NULL,
    actor_id           VARCHAR(128)  NOT NULL,
    actor_roles_json   LONGTEXT      NOT NULL,
    idempotency_key    VARCHAR(128)  NOT NULL,
    created_at         TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_human_interaction_responses PRIMARY KEY (id),
    CONSTRAINT uk_human_interaction_response UNIQUE (interaction_id),
    CONSTRAINT uk_human_response_idempotency UNIQUE (license_code, idempotency_key),
    CONSTRAINT fk_human_response_request
        FOREIGN KEY (interaction_id) REFERENCES human_interaction_requests (id),
    CONSTRAINT chk_human_response_action
        CHECK (action IN ('ANSWER', 'APPROVE', 'REJECT'))
);

CREATE TABLE agent_run_events (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    license_code VARCHAR(128)  NOT NULL,
    root_run_id  VARCHAR(36)   NOT NULL,
    run_id       VARCHAR(36)   NOT NULL,
    event_type   VARCHAR(60)   NOT NULL,
    actor_type   VARCHAR(20)   NOT NULL,
    actor_id     VARCHAR(128)  NULL,
    subject_type VARCHAR(30)   NULL,
    subject_id   VARCHAR(150)  NULL,
    event_json   LONGTEXT      NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_run_events PRIMARY KEY (id),
    CONSTRAINT fk_agent_run_event_root
        FOREIGN KEY (root_run_id) REFERENCES agent_runs (id),
    CONSTRAINT fk_agent_run_event_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id)
);

CREATE INDEX idx_agent_run_event_timeline
    ON agent_run_events (license_code, root_run_id, id);

CREATE TABLE runtime_outbox_events (
    id             VARCHAR(36)   NOT NULL,
    aggregate_id   VARCHAR(36)   NOT NULL,
    event_type     VARCHAR(60)   NOT NULL,
    payload_json   LONGTEXT      NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    attempt_count  INT           NOT NULL DEFAULT 0,
    available_at   TIMESTAMP(6)  NOT NULL,
    claimed_at     TIMESTAMP(6)  NULL,
    published_at   TIMESTAMP(6)  NULL,
    last_error     VARCHAR(2000) NULL,
    created_at     TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_runtime_outbox_events PRIMARY KEY (id),
    CONSTRAINT chk_runtime_outbox_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_runtime_outbox_claim
    ON runtime_outbox_events (status, available_at, created_at);
