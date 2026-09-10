-- Credentials an HTTP tool can present, so a tool can reach an API that requires authentication.
--
-- The secret is stored encrypted (AES-GCM, key from the environment, never in this database) and is
-- returned by no API. A tool's configuration references a credential by name only, because
-- GET /api/v1/tools returns configuration verbatim to every reader in the tenant.

CREATE TABLE tenant_credentials (
    id                CHAR(36)      NOT NULL,
    license_code      VARCHAR(128)  NOT NULL,
    name              VARCHAR(128)  NOT NULL,
    type              VARCHAR(40)   NOT NULL,
    description       VARCHAR(1000) NULL,
    -- v1.<base64url iv>.<base64url ciphertext+tag>
    secret_cipher     LONGTEXT      NOT NULL,
    -- Which key encrypted this row, so a rotated key can still read history.
    key_id            VARCHAR(64)   NOT NULL,
    -- Non-secret settings: header or query parameter name, username, token URL, scopes.
    settings_json     LONGTEXT      NOT NULL,
    -- Reserved: a comma-separated host scope. Not enforced yet; present so restricting a credential
    -- to particular hosts later needs no migration.
    allowed_hosts     VARCHAR(1000) NULL,
    status            VARCHAR(20)   NOT NULL,
    created_at        TIMESTAMP(6)  NOT NULL,
    updated_at        TIMESTAMP(6)  NOT NULL,
    created_by        VARCHAR(128)  NOT NULL,
    updated_by        VARCHAR(128)  NOT NULL,
    CONSTRAINT pk_tenant_credentials PRIMARY KEY (id),
    CONSTRAINT uk_tenant_credentials_tenant_name UNIQUE (license_code, name),
    CONSTRAINT chk_tenant_credentials_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_tenant_credentials_type CHECK (type IN (
        'API_KEY_HEADER', 'API_KEY_QUERY', 'BEARER_STATIC', 'BASIC',
        'OAUTH2_CLIENT_CREDENTIALS', 'GOOGLE_SERVICE_ACCOUNT'))
);

-- Runtime looks a credential up by tenant and name on every authenticated tool call.
CREATE INDEX idx_tenant_credentials_lookup
    ON tenant_credentials (license_code, name, status);

-- Which credential was attached to which tool, and to what host, by whom.
--
-- Credential management is open to anyone who can author a tool, and credentials are not host-scoped,
-- so the tenant egress allowlist is the only barrier between a credential and an arbitrary server.
-- This table does not prevent misuse; it makes it visible after the fact, which is the difference
-- between a leak that is found and one that is not.
CREATE TABLE credential_binding_events (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    license_code   VARCHAR(128)  NOT NULL,
    credential_name VARCHAR(128) NOT NULL,
    tool_id        CHAR(36)      NULL,
    tool_name      VARCHAR(150)  NULL,
    target_host    VARCHAR(255)  NULL,
    action         VARCHAR(30)   NOT NULL,
    actor_id       VARCHAR(128)  NOT NULL,
    occurred_at    TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_credential_binding_events PRIMARY KEY (id),
    CONSTRAINT chk_credential_binding_action CHECK (action IN (
        'CREDENTIAL_CREATED', 'CREDENTIAL_UPDATED', 'CREDENTIAL_DELETED',
        'BOUND_TO_TOOL', 'TOOL_PUBLISHED_WITH_CREDENTIAL'))
);

CREATE INDEX idx_credential_binding_events_tenant
    ON credential_binding_events (license_code, occurred_at);
