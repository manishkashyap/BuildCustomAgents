-- Per-tenant egress allowlist for HTTP tools.
--
-- Replaces the single process-wide agents.tools.http.allowed-hosts property, which could not work
-- on a multi-tenant platform: every tenant registers its own tool hosts. This table answers only
-- "which third-party hosts may THIS tenant's tools reach". It deliberately does not answer "may any
-- tool reach link-local, loopback, or private addresses" - that stays platform policy in Runtime,
-- outside tenant control, so a tenant cannot allowlist its way to cloud metadata.

CREATE TABLE tenant_egress_hosts (
    id            CHAR(36)      NOT NULL,
    license_code  VARCHAR(128)  NOT NULL,
    -- Exact host ("api.stripe.com") or a single-level-or-deeper suffix wildcard ("*.stripe.com").
    host_pattern  VARCHAR(255)  NOT NULL,
    description   VARCHAR(1000) NULL,
    status        VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL,
    updated_at    TIMESTAMP(6)  NOT NULL,
    created_by    VARCHAR(128)  NOT NULL,
    updated_by    VARCHAR(128)  NOT NULL,
    CONSTRAINT pk_tenant_egress_hosts PRIMARY KEY (id),
    CONSTRAINT uk_tenant_egress_hosts_tenant_pattern UNIQUE (license_code, host_pattern),
    CONSTRAINT chk_tenant_egress_hosts_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Runtime reads this on every HTTP tool call, filtered to one tenant's active rows.
CREATE INDEX idx_tenant_egress_hosts_tenant_status
    ON tenant_egress_hosts (license_code, status);

-- Backfill from the hosts already in use, so enforcing the allowlist does not break a tenant whose
-- tools were published while the global property was the only gate.
--
-- Parses the host out of configuration.url by hand: strip the scheme, take everything before the
-- first path separator, then drop any userinfo and port. Templates whose host portion contains a
-- placeholder are skipped - those are rejected going forward, because a variable host means the
-- destination is partly model-controlled.
INSERT INTO tenant_egress_hosts
    (id, license_code, host_pattern, description, status,
     created_at, updated_at, created_by, updated_by)
SELECT
    UUID(),
    backfill.license_code,
    backfill.host,
    'Backfilled from published HTTP tools when the per-tenant allowlist was introduced',
    'ACTIVE',
    NOW(6), NOW(6), 'system-backfill', 'system-backfill'
FROM (
    SELECT DISTINCT
        t.license_code AS license_code,
        LOWER(SUBSTRING_INDEX(
            SUBSTRING_INDEX(
                SUBSTRING_INDEX(
                    SUBSTRING_INDEX(
                        JSON_UNQUOTE(JSON_EXTRACT(t.definition_json, '$.configuration.url')),
                        '://', -1),
                    '/', 1),
                '@', -1),
            ':', 1)) AS host
    FROM custom_tools t
    WHERE t.type = 'HTTP'
      AND t.deleted = FALSE
      AND t.status = 'PUBLISHED'
      AND JSON_EXTRACT(t.definition_json, '$.configuration.url') IS NOT NULL
) AS backfill
WHERE backfill.host <> ''
  AND backfill.host NOT LIKE '%{%'
  AND backfill.host IS NOT NULL;
