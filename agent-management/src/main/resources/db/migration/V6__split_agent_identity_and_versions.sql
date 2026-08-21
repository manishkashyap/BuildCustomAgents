-- Phase 0: separate agent identity (lineage) from agent version.
--
-- custom_agents keeps its id so existing agentId references stay valid, but now
-- holds only lineage-level facts. Definitions move to agent_versions, allowing a
-- published version and a new draft to coexist under one agent.
--
-- Definitions are not migrated: no environment holds data that must be preserved.

DROP TABLE IF EXISTS agent_audit_events;
DROP TABLE IF EXISTS agent_copy_requests;
DROP TABLE IF EXISTS custom_agents;

CREATE TABLE custom_agents (
    id              CHAR(36)      NOT NULL,
    license_code    VARCHAR(128)  NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    normalized_name VARCHAR(120)  NOT NULL,
    description     VARCHAR(1000) NULL,
    status          VARCHAR(20)   NOT NULL,
    active_version  INT           NULL,
    draft_version   INT           NULL,
    next_version    INT           NOT NULL DEFAULT 1,
    deleted         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6)  NOT NULL,
    updated_at      TIMESTAMP(6)  NOT NULL,
    created_by      VARCHAR(128)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(128)  NOT NULL DEFAULT 'system',
    change_reason   VARCHAR(1000) NULL,
    CONSTRAINT pk_custom_agents PRIMARY KEY (id),
    CONSTRAINT uk_custom_agents_tenant_name_deleted
        UNIQUE (license_code, normalized_name, deleted),
    CONSTRAINT chk_custom_agents_status
        CHECK (status IN ('ACTIVE', 'RETIRING', 'RETIRED')),
    CONSTRAINT chk_custom_agents_active_version
        CHECK (active_version IS NULL OR active_version > 0),
    CONSTRAINT chk_custom_agents_draft_version
        CHECK (draft_version IS NULL OR draft_version > 0),
    CONSTRAINT chk_custom_agents_next_version CHECK (next_version > 0)
);

CREATE INDEX idx_custom_agents_tenant_status
    ON custom_agents (license_code, status, deleted);

-- One row per authored revision. The single active_version / draft_version slots on
-- the lineage structurally allow at most one serving and one editable version each.
CREATE TABLE agent_versions (
    id              CHAR(36)      NOT NULL,
    agent_id        CHAR(36)      NOT NULL,
    license_code    VARCHAR(128)  NOT NULL,
    version         INT           NOT NULL,
    definition_json LONGTEXT      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    updated_at      TIMESTAMP(6)  NOT NULL,
    created_by      VARCHAR(128)  NOT NULL,
    updated_by      VARCHAR(128)  NOT NULL,
    change_reason   VARCHAR(1000) NULL,
    CONSTRAINT pk_agent_versions PRIMARY KEY (id),
    CONSTRAINT uk_agent_versions_agent_version UNIQUE (agent_id, version),
    CONSTRAINT fk_agent_versions_agent
        FOREIGN KEY (agent_id) REFERENCES custom_agents (id),
    CONSTRAINT chk_agent_versions_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'RETIRED')),
    CONSTRAINT chk_agent_versions_version CHECK (version > 0)
);

CREATE INDEX idx_agent_versions_tenant_status
    ON agent_versions (license_code, status);

CREATE TABLE agent_copy_requests (
    idempotency_key VARCHAR(200) NOT NULL,
    license_code    VARCHAR(128) NOT NULL,
    request_sha256  CHAR(64)     NOT NULL,
    copied_agent_id CHAR(36)     NOT NULL,
    created_by      VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_copy_requests PRIMARY KEY (license_code, idempotency_key),
    CONSTRAINT fk_agent_copy_requests_agent
        FOREIGN KEY (copied_agent_id) REFERENCES custom_agents (id)
);

CREATE TABLE agent_audit_events (
    id            CHAR(36)      NOT NULL,
    agent_id      CHAR(36)      NOT NULL,
    agent_version INT           NULL,
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
