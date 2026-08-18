package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import org.springframework.stereotype.Component;

@Component
public class CustomAgentToolExecutor implements ToolExecutor {

    @Override
    public ToolType type() {
        return ToolType.CUSTOM_AGENT;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        JsonNode configuration = request.tool().configuration();
        String childAgentId = configuration.path("agentId").asText("");
        int childAgentVersion = configuration.path("agentVersion").asInt(0);
        String task = request.arguments().path("task").asText("");
        JsonNode input = request.arguments().path("input");
        if (input.isMissingNode() || input.isNull()) {
            input = JsonNodeFactory.instance.objectNode();
        }
        JsonNode settings = configuration.path("settings");
        if (settings.isMissingNode() || settings.isNull()) {
            settings = JsonNodeFactory.instance.objectNode();
        }
        try {
            return request.context().agentInvoker().invoke(new AgentToolInvocationRequest(
                    request.tool(), request.context(), childAgentId, childAgentVersion,
                    task, input, settings));
        } catch (IllegalArgumentException exception) {
            throw new AgentExecutionException(
                    "CUSTOM_AGENT tool " + request.tool().name() + " has an invalid definition or invocation",
                    exception);
        }
    }
}
