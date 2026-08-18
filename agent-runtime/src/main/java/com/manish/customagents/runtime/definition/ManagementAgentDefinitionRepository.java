package com.manish.customagents.runtime.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ManagementAgentDefinitionRepository {

    private static final String FIND_AGENT_SQL = """
            SELECT id, license_code, status, version, definition_json
            FROM custom_agents
            WHERE id = ? AND license_code = ? AND deleted = false
            """;

    private final ManagementDatabaseClient database;
    private final ObjectMapper objectMapper;

    public ManagementAgentDefinitionRepository(
            ManagementDatabaseClient database,
            ObjectMapper objectMapper) {
        this.database = database;
        this.objectMapper = objectMapper;
    }

    public StoredAgentDefinition get(String licenseCode, String agentId) {
        List<StoredAgentDefinition> matches = database.jdbcTemplate().query(
                FIND_AGENT_SQL,
                (resultSet, rowNumber) -> new StoredAgentDefinition(
                        resultSet.getString("id"),
                        resultSet.getString("license_code"),
                        resultSet.getString("status"),
                        resultSet.getInt("version"),
                        readDefinition(agentId, resultSet.getString("definition_json"))),
                agentId,
                licenseCode);
        if (matches.isEmpty()) {
            throw new AgentNotFoundException(agentId);
        }
        StoredAgentDefinition agent = matches.getFirst();
        if ("RETIRING".equals(agent.status())) {
            throw new AgentRetiringException(agentId);
        }
        if (!"PUBLISHED".equals(agent.status())) {
            throw new AgentNotPublishedException(agentId, agent.status());
        }
        return agent;
    }

    public DraftAgentDefinition getDraftForTest(String licenseCode, String agentId) {
        List<DraftAgentDefinition> matches = database.jdbcTemplate().query(
                FIND_AGENT_SQL,
                (resultSet, rowNumber) -> {
                    String definitionJson = resultSet.getString("definition_json");
                    StoredAgentDefinition stored = new StoredAgentDefinition(
                            resultSet.getString("id"), resultSet.getString("license_code"),
                            resultSet.getString("status"), resultSet.getInt("version"),
                            readDefinition(agentId, definitionJson));
                    return new DraftAgentDefinition(stored, sha256(definitionJson));
                },
                agentId,
                licenseCode);
        if (matches.isEmpty()) throw new AgentNotFoundException(agentId);
        DraftAgentDefinition draft = matches.getFirst();
        if (!"DRAFT".equals(draft.agent().status())) {
            throw new AgentNotDraftException(agentId, draft.agent().status());
        }
        return draft;
    }

    public StoredAgentDefinition getForExistingRun(
            String licenseCode, String agentId, int expectedVersion) {
        List<StoredAgentDefinition> matches = database.jdbcTemplate().query(
                FIND_AGENT_SQL,
                (resultSet, rowNumber) -> new StoredAgentDefinition(
                        resultSet.getString("id"), resultSet.getString("license_code"),
                        resultSet.getString("status"), resultSet.getInt("version"),
                        readDefinition(agentId, resultSet.getString("definition_json"))),
                agentId, licenseCode);
        if (matches.isEmpty()) throw new AgentNotFoundException(agentId);
        StoredAgentDefinition agent = matches.getFirst();
        if (agent.version() != expectedVersion) {
            throw new InvalidAgentDefinitionException(agentId,
                    new IllegalStateException("Persisted run version does not match agent definition"));
        }
        if (!List.of("PUBLISHED", "RETIRING").contains(agent.status())) {
            throw new AgentNotPublishedException(agentId, agent.status());
        }
        return agent;
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
