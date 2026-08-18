ALTER TABLE custom_tools
    DROP CHECK chk_custom_tools_type;

ALTER TABLE custom_tools
    ADD CONSTRAINT chk_custom_tools_type
        CHECK (type IN ('HTTP', 'MCP', 'SQL_QUERY', 'FUNCTION', 'BUILT_IN', 'CUSTOM_AGENT'));
