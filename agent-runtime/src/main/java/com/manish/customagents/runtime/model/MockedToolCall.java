package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;

public record MockedToolCall(
        String toolName,
        String operation,
        JsonNode arguments,
        JsonNode result) {

    public MockedToolCall {
        arguments = arguments == null ? null : arguments.deepCopy();
        result = result == null ? null : result.deepCopy();
    }
}
