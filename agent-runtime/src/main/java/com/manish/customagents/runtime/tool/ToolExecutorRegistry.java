package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutorRegistry {

    private final Map<ToolType, ToolExecutor> executors;

    public ToolExecutorRegistry(List<ToolExecutor> toolExecutors) {
        EnumMap<ToolType, ToolExecutor> indexed = new EnumMap<>(ToolType.class);
        for (ToolExecutor executor : toolExecutors) {
            if (indexed.putIfAbsent(executor.type(), executor) != null) {
                throw new IllegalStateException("Duplicate ToolExecutor for type " + executor.type());
            }
        }
        this.executors = Map.copyOf(indexed);
    }

    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (mustMock(request)) {
            return mock(request);
        }
        ToolType type = request.tool().type();
        ToolExecutor executor = executors.get(type);
        if (executor == null) {
            throw new UnsupportedToolTypeException(type);
        }
        return executor.execute(request);
    }

    public boolean supports(ToolType type) {
        return executors.containsKey(type);
    }

    private boolean mustMock(ToolExecutionRequest request) {
        if (request.context().executionMode() != ExecutionMode.DRAFT_TEST) return false;
        if (request.tool().type() == ToolType.BUILT_IN
                || request.tool().type() == ToolType.CUSTOM_AGENT) return false;
        return !"READ".equals(operation(request.tool()));
    }

    private ToolExecutionResult mock(ToolExecutionRequest request) {
        PublishedToolDefinition tool = request.tool();
        JsonNode configured = request.context().mockToolResults().get(tool.name());
        JsonNode output;
        if (configured != null) {
            output = configured.deepCopy();
        } else {
            ObjectNode generated = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("testMode", true)
                    .put("mocked", true)
                    .put("status", "SIMULATED_SUCCESS")
                    .put("toolName", tool.name())
                    .put("operation", operation(tool).isEmpty() ? "UNCLASSIFIED" : operation(tool))
                    .put("message", "No external side effect was performed.");
            generated.set("arguments", request.arguments().deepCopy());
            output = generated;
        }
        return new ToolExecutionResult(output, Map.of(
                "testMode", "true",
                "mocked", "true",
                "operation", operation(tool).isEmpty() ? "UNCLASSIFIED" : operation(tool)));
    }

    private String operation(PublishedToolDefinition tool) {
        return tool.executionPolicy().path("operation").asText("").strip().toUpperCase(Locale.ROOT);
    }
}
