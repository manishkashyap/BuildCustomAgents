CREATE TABLE agent_runs (
    id              VARCHAR(36)  NOT NULL,
    license_code    VARCHAR(128) NOT NULL,
    agent_id        VARCHAR(36)  NOT NULL,
    agent_version   INT          NOT NULL,
    provider        VARCHAR(40)  NOT NULL,
    model           VARCHAR(150) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    input_json      LONGTEXT     NOT NULL,
    output_json     LONGTEXT     NULL,
    error_message   VARCHAR(2000) NULL,
    started_at      TIMESTAMP(6) NOT NULL,
    completed_at    TIMESTAMP(6) NULL,
    CONSTRAINT pk_agent_runs PRIMARY KEY (id),
    CONSTRAINT chk_agent_runs_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_agent_runs_tenant_started
    ON agent_runs (license_code, started_at);
CREATE INDEX idx_agent_runs_agent_version
    ON agent_runs (license_code, agent_id, agent_version);

CREATE TABLE agent_run_turns (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(36)  NOT NULL,
    turn_number     INT          NOT NULL,
    request_json    LONGTEXT     NOT NULL,
    response_json   LONGTEXT     NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_run_turns PRIMARY KEY (id),
    CONSTRAINT uk_agent_run_turn UNIQUE (run_id, turn_number),
    CONSTRAINT fk_agent_run_turn_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id)
);

CREATE TABLE agent_tool_invocations (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    run_id          VARCHAR(36)   NOT NULL,
    turn_number     INT           NOT NULL,
    tool_call_id    VARCHAR(150)  NOT NULL,
    tool_name       VARCHAR(150)  NOT NULL,
    arguments_json  LONGTEXT      NOT NULL,
    result_json     LONGTEXT      NULL,
    status          VARCHAR(20)   NOT NULL,
    error_message   VARCHAR(2000) NULL,
    duration_ms     BIGINT        NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_agent_tool_invocations PRIMARY KEY (id),
    CONSTRAINT uk_agent_tool_call UNIQUE (run_id, tool_call_id),
    CONSTRAINT fk_agent_tool_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (id),
    CONSTRAINT chk_agent_tool_status
        CHECK (status IN ('SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_agent_tool_run_turn
    ON agent_tool_invocations (run_id, turn_number);
