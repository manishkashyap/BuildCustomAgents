package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.manish.customagents.runtime.enums.HumanAudienceType;
import com.manish.customagents.runtime.enums.HumanInteractionType;
import com.manish.customagents.runtime.enums.HumanResponseType;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.manish.customagents.contracts.ToolType;

@Component
public class BuiltInToolExecutor implements ToolExecutor {
    public static final String REQUEST_CLARIFICATION = "request_clarification";

    @Override
    public ToolType type() {
        return ToolType.BUILT_IN;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (!REQUEST_CLARIFICATION.equals(request.tool().name())) {
            throw new AgentExecutionException("Unsupported built-in tool: " + request.tool().name());
        }
        JsonNode arguments = request.arguments();
        String question = required(arguments, "question");
        String reason = required(arguments, "reason");
        String category = required(arguments, "category");
        HumanResponseType responseType = parseResponseType(required(arguments, "responseType"));
        HumanAudienceType audience = switch (arguments.path("audienceHint").asText("RUN_REQUESTER")) {
            case "CALLER_AGENT" -> HumanAudienceType.CALLER_AGENT;
            case "CONFIGURED_ROLE_OR_GROUP" -> HumanAudienceType.ROLE;
            default -> HumanAudienceType.RUN_REQUESTER;
        };
        List<String> values = new ArrayList<>();
        arguments.path("audienceValues").forEach(value -> values.add(value.asText()));
        return ToolExecutionResult.waiting(new HumanInteractionRequestSpec(
                HumanInteractionType.CLARIFICATION,
                category,
                question,
                reason,
                responseType,
                arguments,
                audience,
                values,
                Duration.ofHours(24)));
    }

    private String required(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("").strip();
        if (value.isEmpty()) {
            throw new AgentExecutionException("request_clarification requires " + field);
        }
        return value;
    }

    private HumanResponseType parseResponseType(String value) {
        try {
            HumanResponseType parsed = HumanResponseType.valueOf(value);
            if (parsed == HumanResponseType.APPROVAL) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new AgentExecutionException("Unsupported clarification responseType: " + value);
        }
    }
}
