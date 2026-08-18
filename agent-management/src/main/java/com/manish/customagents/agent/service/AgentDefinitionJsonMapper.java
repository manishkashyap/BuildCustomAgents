package com.manish.customagents.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import org.springframework.stereotype.Component;

@Component
public class AgentDefinitionJsonMapper {

    private final ObjectMapper objectMapper;

    public AgentDefinitionJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(CreateCustomAgentRequest definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize the agent definition", exception);
        }
    }

    CreateCustomAgentRequest read(String definitionJson) {
        try {
            return objectMapper.readValue(definitionJson, CreateCustomAgentRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize the agent definition", exception);
        }
    }

    JsonNode tree(CreateCustomAgentRequest definition) {
        return objectMapper.valueToTree(definition);
    }

    CreateCustomAgentRequest convert(JsonNode definition) {
        try {
            return objectMapper.treeToValue(definition, CreateCustomAgentRequest.class);
        } catch (JsonProcessingException exception) {
            throw new InvalidAgentDefinitionRequestException(
                    "The patch does not produce a valid custom agent definition");
        }
    }
}
