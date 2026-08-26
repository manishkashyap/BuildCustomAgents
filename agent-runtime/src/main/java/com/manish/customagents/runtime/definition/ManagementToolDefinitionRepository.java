package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.tool.PublishedToolDefinition;
import com.manish.customagents.runtime.tool.PublishedToolNotFoundException;
import com.manish.customagents.runtime.tool.StoredToolPayload;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class ManagementToolDefinitionRepository {

    private static final String FIND_TOOL_SQL = """
            SELECT id, version, definition_json
            FROM custom_tools
            WHERE license_code = ? AND normalized_name = ?
              AND status = 'PUBLISHED' AND deleted = false
            """;

    private final ManagementDatabaseClient database;
    private final ObjectMapper objectMapper;

    public ManagementToolDefinitionRepository(
            ManagementDatabaseClient database,
            ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public List<PublishedToolDefinition> resolve(String licenseCode, Set<String> toolNames) {
        return toolNames.stream().map(name -> get(licenseCode, name)).toList();
    }

    private PublishedToolDefinition get(String licenseCode, String toolName) {
        List<PublishedToolDefinition> matches = database.jdbcTemplate().query(
                FIND_TOOL_SQL,
                (resultSet, rowNumber) -> read(
                        resultSet.getString("id"),
                        resultSet.getInt("version"),
                        resultSet.getString("definition_json")),
                licenseCode,
                toolName.strip().toLowerCase(Locale.ROOT));
        if (matches.isEmpty()) {
            throw new PublishedToolNotFoundException(toolName);
        }
        return matches.getFirst();
    }

    private PublishedToolDefinition read(String id, int version, String definitionJson) {
        try {
            StoredToolPayload payload = objectMapper.readValue(definitionJson, StoredToolPayload.class);
            return new PublishedToolDefinition(
                    id, payload.name(), payload.description(), payload.type(), version,
                    payload.inputSchema(), payload.outputSchema(), payload.configuration(),
                    payload.executionPolicy());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Stored definition for dynamic tool " + id + " is invalid", exception);
        }
    }
}
