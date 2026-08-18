package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

public record GenerationOptions(
        Double temperature,
        Integer maxOutputTokens,
        Boolean parallelToolCalls,
        JsonNode responseSchema) {

    public GenerationOptions {
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be greater than zero");
        }
        if (responseSchema != null) {
            if (!responseSchema.isObject()) {
                throw new IllegalArgumentException("responseSchema must be a JSON object");
            }
            responseSchema = responseSchema.deepCopy();
        }
    }

    public static GenerationOptions defaults() {
        return new GenerationOptions(null, null, true, null);
    }
}
