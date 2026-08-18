package com.manish.customagents.runtime.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.model.ModelProvider;

public final class ProviderResponseSupport {

    private ProviderResponseSupport() {
    }

    public static ObjectNode parseObject(
            ObjectMapper objectMapper,
            String value,
            ModelProvider provider,
            String model,
            String fieldName) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new GenericAgentProviderException(
                        provider,
                        model,
                        "%s returned by %s must be a JSON object".formatted(fieldName, provider));
            }
            return (ObjectNode) parsed;
        } catch (JsonProcessingException exception) {
            throw new GenericAgentProviderException(
                    provider,
                    model,
                    "%s returned by %s is not valid JSON".formatted(fieldName, provider),
                    null,
                    exception);
        }
    }
}
