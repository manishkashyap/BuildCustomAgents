package com.manish.customagents.contracts;

/**
 * The kinds of tool a definition may declare.
 *
 * <p>Shared because it is a wire contract: management persists the name, the runtime resolves an
 * executor from it, and V4__add_custom_agent_tool_type.sql constrains the stored column. Adding a
 * value here means adding a migration and, before anything can use it, an executor.
 *
 * @see ExecutableToolTypes
 */
public enum ToolType {
    HTTP,
    MCP,
    SQL_QUERY,
    FUNCTION,
    BUILT_IN,
    CUSTOM_AGENT
}
