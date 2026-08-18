package com.manish.customagents.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.tool.model.CreateToolRequest;
import org.springframework.stereotype.Component;

@Component
public class ToolDefinitionJsonMapper {

    private final ObjectMapper objectMapper;

    public ToolDefinitionJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(CreateToolRequest definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize the tool definition", exception);
        }
    }

    CreateToolRequest read(String definitionJson) {
        try {
            return objectMapper.readValue(definitionJson, CreateToolRequest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize the tool definition", exception);
        }
    }
}
