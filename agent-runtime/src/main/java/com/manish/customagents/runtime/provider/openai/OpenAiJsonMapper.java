package com.manish.customagents.runtime.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.provider.GenericAgentProviderException;
import com.manish.customagents.runtime.provider.ProviderResponseSupport;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.GenerationOptions;
import com.manish.customagents.runtime.model.MessageRole;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.model.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiJsonMapper {

    private final ObjectMapper objectMapper;

    OpenAiJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode toProviderRequest(BaseAgentRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model().model());
        root.set("messages", mapMessages(request));
        root.put("store", false);

        if (!request.tools().isEmpty()) {
            root.set("tools", mapTools(request.tools()));
            if (request.options().parallelToolCalls() != null) {
                root.put("parallel_tool_calls", request.options().parallelToolCalls());
            }
        }
        mapGenerationOptions(root, request.options());
        return root;
    }

    BaseAgentResponse fromProviderResponse(JsonNode response, String requestedModel) {
        JsonNode choice = response.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new GenericAgentProviderException(
                    ModelProvider.OPENAI,
                    requestedModel,
                    "OpenAI response did not contain a completion choice");
        }

        JsonNode message = choice.path("message");
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"), requestedModel);
        FinishReason finishReason = toolCalls.isEmpty()
                ? mapFinishReason(choice.path("finish_reason").asText())
                : FinishReason.TOOL_CALLS;

        JsonNode usage = response.path("usage");
        int inputTokens = usage.path("prompt_tokens").asInt(0);
        int outputTokens = usage.path("completion_tokens").asInt(0);
        int totalTokens = Math.max(usage.path("total_tokens").asInt(0), inputTokens + outputTokens);

        Map<String, String> metadata = new LinkedHashMap<>();
        putText(metadata, "providerModel", response.path("model"));
        putText(metadata, "systemFingerprint", response.path("system_fingerprint"));
        putText(metadata, "providerFinishReason", choice.path("finish_reason"));

        return new BaseAgentResponse(
                textOrNull(response.path("id")),
                text,
                toolCalls,
                finishReason,
                new TokenUsage(inputTokens, outputTokens, totalTokens),
                metadata);
    }

    private ArrayNode mapMessages(BaseAgentRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (AgentMessage message : request.messages()) {
            if (!message.attachments().isEmpty()) {
                throw new GenericAgentProviderException(
                        ModelProvider.OPENAI,
                        request.model().model(),
                        "The OpenAI Chat Completions adapter does not support attachment references yet");
            }

            ObjectNode providerMessage = messages.addObject();
            providerMessage.put("role", mapRole(message.role()));
            switch (message.role()) {
                case SYSTEM, USER -> providerMessage.put("content", message.content());
                case ASSISTANT -> mapAssistantMessage(providerMessage, message);
                case TOOL -> {
                    providerMessage.put("content", message.content());
                    providerMessage.put("tool_call_id", message.toolCallId());
                }
            }
        }
        return messages;
    }

    private void mapAssistantMessage(ObjectNode providerMessage, AgentMessage message) {
        if (message.content().isBlank() && !message.toolCalls().isEmpty()) {
            providerMessage.putNull("content");
        } else {
            providerMessage.put("content", message.content());
        }
        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = providerMessage.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode providerCall = calls.addObject();
                providerCall.put("id", call.id());
                providerCall.put("type", "function");
                ObjectNode function = providerCall.putObject("function");
                function.put("name", call.name());
                function.put("arguments", call.arguments().toString());
            }
        }
    }

    private ArrayNode mapTools(List<ToolDefinition> tools) {
        ArrayNode providerTools = objectMapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode providerTool = providerTools.addObject();
            providerTool.put("type", "function");
            ObjectNode function = providerTool.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.inputSchema());
        }
        return providerTools;
    }

    private void mapGenerationOptions(ObjectNode root, GenerationOptions options) {
        if (options.temperature() != null) {
            root.put("temperature", options.temperature());
        }
        if (options.maxOutputTokens() != null) {
            root.put("max_completion_tokens", options.maxOutputTokens());
        }
        if (options.responseSchema() != null) {
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "custom_agent_response");
            jsonSchema.set("schema", options.responseSchema());
            jsonSchema.put("strict", false);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode, String requestedModel) {
        if (!toolCallsNode.isArray()) {
            return List.of();
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode providerCall : toolCallsNode) {
            String id = textOrNull(providerCall.path("id"));
            String name = textOrNull(providerCall.path("function").path("name"));
            String arguments = providerCall.path("function").path("arguments").asText(null);
            if (id == null || name == null || arguments == null) {
                throw new GenericAgentProviderException(
                        ModelProvider.OPENAI,
                        requestedModel,
                        "OpenAI returned an incomplete function call");
            }
            toolCalls.add(new ToolCall(
                    id,
                    name,
                    ProviderResponseSupport.parseObject(
                            objectMapper,
                            arguments,
                            ModelProvider.OPENAI,
                            requestedModel,
                            "Function arguments")));
        }
        return List.copyOf(toolCalls);
    }

    private static String mapRole(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private static FinishReason mapFinishReason(String value) {
        return switch (value) {
            case "stop" -> FinishReason.STOP;
            case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.MAX_TOKENS;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static void putText(Map<String, String> metadata, String key, JsonNode value) {
        String text = textOrNull(value);
        if (text != null) {
            metadata.put(key, text);
        }
    }

    private static String textOrNull(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }
}
