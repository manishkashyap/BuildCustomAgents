CREATE TABLE custom_tools (
    id              CHAR(36)      NOT NULL,
    license_code    VARCHAR(128)  NOT NULL,
    name            VARCHAR(150)  NOT NULL,
    normalized_name VARCHAR(150)  NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    type            VARCHAR(30)   NOT NULL,
    definition_json LONGTEXT      NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    version         INT           NOT NULL,
    deleted         BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP(6)  NOT NULL,
    updated_at      TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_custom_tools PRIMARY KEY (id),
    CONSTRAINT uk_custom_tools_tenant_name_deleted
        UNIQUE (license_code, normalized_name, deleted),
    CONSTRAINT chk_custom_tools_version CHECK (version > 0),
    CONSTRAINT chk_custom_tools_type
        CHECK (type IN ('HTTP', 'MCP', 'SQL_QUERY', 'FUNCTION', 'BUILT_IN')),
    CONSTRAINT chk_custom_tools_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED'))
);

CREATE INDEX idx_custom_tools_tenant_status
    ON custom_tools (license_code, status, deleted);
