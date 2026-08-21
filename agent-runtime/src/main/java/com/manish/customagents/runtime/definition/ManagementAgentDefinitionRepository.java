package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ManagementAgentDefinitionRepository {

    /** Resolves the version an identity currently serves. */
    private static final String FIND_ACTIVE_SQL = """
            SELECT a.id, a.license_code, a.status AS lineage_status,
                   v.version, v.status AS version_status, v.definition_json
            FROM custom_agents a
            JOIN agent_versions v ON v.agent_id = a.id AND v.version = a.active_version
            WHERE a.id = ? AND a.license_code = ? AND a.deleted = false
            """;

    /** Resolves the editable version of an identity. */
    private static final String FIND_DRAFT_SQL = """
            SELECT a.id, a.license_code, a.status AS lineage_status,
                   v.version, v.status AS version_status, v.definition_json
            FROM custom_agents a
            JOIN agent_versions v ON v.agent_id = a.id AND v.version = a.draft_version
            WHERE a.id = ? AND a.license_code = ? AND a.deleted = false
            """;

    /** Resolves one explicit version, whether or not it still serves new runs. */
    private static final String FIND_VERSION_SQL = """
            SELECT a.id, a.license_code, a.status AS lineage_status,
                   v.version, v.status AS version_status, v.definition_json
            FROM custom_agents a
            JOIN agent_versions v ON v.agent_id = a.id
            WHERE a.id = ? AND a.license_code = ? AND v.version = ? AND a.deleted = false
            """;

    /** Statuses whose definition may still serve a run that already started against it. */
    private static final List<String> RESUMABLE_VERSION_STATUSES =
            List.of("PUBLISHED", "SUPERSEDED");

    private final ManagementDatabaseClient database;
    private final ObjectMapper objectMapper;

    public ManagementAgentDefinitionRepository(
            ManagementDatabaseClient database,
            ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public StoredAgentDefinition get(String licenseCode, String agentId) {
        StoredAgentDefinition agent = queryOne(FIND_ACTIVE_SQL, agentId, licenseCode);
        if (agent == null) {
            // Either no such agent, or an agent that has never published a version.
            if (exists(licenseCode, agentId)) {
                throw new AgentNotPublishedException(agentId, "DRAFT");
            }
            throw new AgentNotFoundException(agentId);
        }
        if ("RETIRING".equals(agent.lineageStatus())) {
            throw new AgentRetiringException(agentId);
        }
        if (!"PUBLISHED".equals(agent.status())) {
            throw new AgentNotPublishedException(agentId, agent.status());
        }
        return agent;
    }

    public DraftAgentDefinition getDraftForTest(String licenseCode, String agentId) {
        List<DraftAgentDefinition> matches = database.jdbcTemplate().query(
                FIND_DRAFT_SQL,
                (resultSet, rowNumber) -> new DraftAgentDefinition(
                        map(agentId, resultSet), sha256(resultSet.getString("definition_json"))),
                agentId,
                licenseCode);
        if (matches.isEmpty()) {
            if (exists(licenseCode, agentId)) {
                throw new AgentNotDraftException(agentId, "PUBLISHED");
            }
            throw new AgentNotFoundException(agentId);
        }
        DraftAgentDefinition draft = matches.getFirst();
        if (!"DRAFT".equals(draft.agent().status())) {
            throw new AgentNotDraftException(agentId, draft.agent().status());
        }
        return draft;
    }

    /**
     * Loads the exact version a persisted run started against, so a run that paused for a human
     * resumes on its own definition even after a newer version was published.
     */
    public StoredAgentDefinition getForExistingRun(
            String licenseCode, String agentId, int expectedVersion) {
        StoredAgentDefinition agent = queryOne(
                FIND_VERSION_SQL, agentId, licenseCode, expectedVersion);
        if (agent == null) {
            if (exists(licenseCode, agentId)) {
                throw new InvalidAgentDefinitionException(agentId, new IllegalStateException(
                        "Persisted run version " + expectedVersion + " no longer exists"));
            }
            throw new AgentNotFoundException(agentId);
        }
        if (!RESUMABLE_VERSION_STATUSES.contains(agent.status())) {
            throw new AgentNotPublishedException(agentId, agent.status());
        }
        return agent;
    }

    private boolean exists(String licenseCode, String agentId) {
        Integer count = database.jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM custom_agents WHERE id = ? AND license_code = ? AND deleted = false",
                Integer.class, agentId, licenseCode);
        return count != null && count > 0;
    }

    private StoredAgentDefinition queryOne(String sql, Object... arguments) {
        List<StoredAgentDefinition> matches = database.jdbcTemplate().query(
                sql, rowMapper(String.valueOf(arguments[0])), arguments);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private RowMapper<StoredAgentDefinition> rowMapper(String agentId) {
        return (resultSet, rowNumber) -> map(agentId, resultSet);
    }

    private StoredAgentDefinition map(String agentId, java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new StoredAgentDefinition(
                resultSet.getString("id"),
                resultSet.getString("license_code"),
                resultSet.getString("version_status"),
                resultSet.getString("lineage_status"),
                resultSet.getInt("version"),
                readDefinition(agentId, resultSet.getString("definition_json")));
    }

    private PublishedAgentDefinition readDefinition(String agentId, String definitionJson) {
        try {
            return objectMapper.readValue(definitionJson, PublishedAgentDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidAgentDefinitionException(agentId, exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
