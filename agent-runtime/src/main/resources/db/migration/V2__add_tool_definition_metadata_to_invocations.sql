ALTER TABLE agent_tool_invocations
    ADD COLUMN tool_id VARCHAR(36) NULL AFTER tool_name,
    ADD COLUMN tool_version INT NULL AFTER tool_id,
    ADD COLUMN tool_type VARCHAR(30) NULL AFTER tool_version;

CREATE INDEX idx_agent_tool_definition
    ON agent_tool_invocations (tool_id, tool_version);
