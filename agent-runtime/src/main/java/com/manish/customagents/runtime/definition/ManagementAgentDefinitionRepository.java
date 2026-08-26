package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.JsonDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ManagementAgentDefinitionRepository {

    /**
     * One projection, three join predicates. The LEFT JOIN means a row always comes back when
     * the identity exists, so "no such agent" and "agent has no matching version" are answered
     * in a single round trip instead of a lookup followed by a COUNT.
     *
     * <p>Placeholders bind in textual order. Where the predicate itself is parameterised the
     * version is therefore the <em>first</em> argument, ahead of the id and licence code.
     */
    private static final String FIND_TEMPLATE = """
            SELECT a.id, a.license_code, a.status AS lineage_status,
                   v.version, v.status AS version_status, v.definition_json
            FROM custom_agents a
            LEFT JOIN agent_versions v ON v.agent_id = a.id AND v.version = %s
            WHERE a.id = ? AND a.license_code = ? AND a.deleted = false
            """;

    private static final String FIND_ACTIVE_SQL = FIND_TEMPLATE.formatted("a.active_version");
    private static final String FIND_DRAFT_SQL = FIND_TEMPLATE.formatted("a.draft_version");
    private static final String FIND_VERSION_SQL = FIND_TEMPLATE.formatted("?");

    /** Statuses whose definition may still serve a run that already started against it. */
    private static final Set<String> RESUMABLE_VERSION_STATUSES = Set.of("PUBLISHED", "SUPERSEDED");

    private final ManagementDatabaseClient database;
    private final ObjectMapper objectMapper;

    public ManagementAgentDefinitionRepository(
            ManagementDatabaseClient database,
            ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public StoredAgentDefinition get(String licenseCode, String agentId) {
        AgentRow row = queryOne(FIND_ACTIVE_SQL, agentId, agentId, licenseCode);
        if (row == null) throw new AgentNotFoundException(agentId);
        if ("RETIRING".equals(row.lineageStatus())) throw new AgentRetiringException(agentId);
        // No row on the version side means the identity has never published a version.
        if (!row.hasVersion()) throw new AgentNotPublishedException(agentId, "DRAFT");
        if (!"PUBLISHED".equals(row.versionStatus())) {
            throw new AgentNotPublishedException(agentId, row.versionStatus());
        }
        return toDefinition(agentId, row);
    }

    public DraftAgentDefinition getDraftForTest(String licenseCode, String agentId) {
        AgentRow row = queryOne(FIND_DRAFT_SQL, agentId, agentId, licenseCode);
        if (row == null) throw new AgentNotFoundException(agentId);
        if (!row.hasVersion()) throw new AgentNotDraftException(agentId, "PUBLISHED");
        if (!"DRAFT".equals(row.versionStatus())) {
            throw new AgentNotDraftException(agentId, row.versionStatus());
        }
        return new DraftAgentDefinition(
                toDefinition(agentId, row), JsonDigest.sha256(row.definitionJson()));
    }

    /**
     * Loads the exact version a persisted run started against, so a run that paused for a human
     * resumes on its own definition even after a newer version was published.
     */
    public StoredAgentDefinition getForExistingRun(
            String licenseCode, String agentId, int expectedVersion) {
        AgentRow row = queryOne(FIND_VERSION_SQL, agentId, expectedVersion, agentId, licenseCode);
        if (row == null) throw new AgentNotFoundException(agentId);
        if (!row.hasVersion()) {
            throw new InvalidAgentDefinitionException(agentId, new IllegalStateException(
                    "Persisted run version " + expectedVersion + " no longer exists"));
        }
        if (!RESUMABLE_VERSION_STATUSES.contains(row.versionStatus())) {
            throw new AgentNotPublishedException(agentId, row.versionStatus());
        }
        return toDefinition(agentId, row);
    }

    private AgentRow queryOne(String sql, String agentId, Object... arguments) {
        List<AgentRow> matches = database.jdbcTemplate().query(sql, rowMapper(agentId), arguments);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private RowMapper<AgentRow> rowMapper(String agentId) {
        return (resultSet, rowNumber) -> map(agentId, resultSet);
    }

    private AgentRow map(String agentId, ResultSet resultSet) throws SQLException {
        int version = resultSet.getInt("version");
        boolean versionPresent = !resultSet.wasNull();
        return new AgentRow(
                resultSet.getString("id"),
                resultSet.getString("license_code"),
                resultSet.getString("lineage_status"),
                versionPresent ? version : null,
                resultSet.getString("version_status"),
                resultSet.getString("definition_json"),
                agentId);
    }

    private StoredAgentDefinition toDefinition(String agentId, AgentRow row) {
        return new StoredAgentDefinition(
                row.id(), row.licenseCode(), row.versionStatus(), row.lineageStatus(),
                row.version(), readDefinition(agentId, row.definitionJson()));
    }

    private PublishedAgentDefinition readDefinition(String agentId, String definitionJson) {
        try {
            return objectMapper.readValue(definitionJson, PublishedAgentDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidAgentDefinitionException(agentId, exception);
        }
    }

    /** One joined row. A null {@code version} means the identity matched but no version did. */
    private record AgentRow(
            String id,
            String licenseCode,
            String lineageStatus,
            Integer version,
            String versionStatus,
            String definitionJson,
            String requestedAgentId) {

        private boolean hasVersion() {
            return version != null;
        }
    }
}
