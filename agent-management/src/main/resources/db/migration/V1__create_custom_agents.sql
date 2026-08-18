CREATE TABLE custom_agents (
    id              CHAR(36)      NOT NULL,
    license_code    VARCHAR(128)  NOT NULL,
    name            VARCHAR(120)  NOT NULL,
    normalized_name VARCHAR(120)  NOT NULL,
    description     VARCHAR(1000) NULL,
    definition_json LONGTEXT      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    version         INT           NOT NULL,
    deleted         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6)  NOT NULL,
    updated_at      TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_custom_agents PRIMARY KEY (id),
    CONSTRAINT uk_custom_agents_tenant_name_deleted
        UNIQUE (license_code, normalized_name, deleted),
    CONSTRAINT chk_custom_agents_version CHECK (version > 0),
    CONSTRAINT chk_custom_agents_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE INDEX idx_custom_agents_tenant_status
    ON custom_agents (license_code, status, deleted);
