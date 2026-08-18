package com.manish.customagents.runtime.provider.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.manish.customagents.runtime.provider.GenericAgentProviderException;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.AttachmentReference;
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
import java.util.UUID;
import java.util.stream.Collectors;

final class GeminiJsonMapper {

    static final String THOUGHT_SIGNATURE_METADATA_KEY = "gemini.thoughtSignature";

    private final ObjectMapper objectMapper;

    GeminiJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ObjectNode toProviderRequest(BaseAgentRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        mapSystemInstruction(root, request.messages());
        ArrayNode contents = root.putArray("contents");
        for (AgentMessage message : request.messages()) {
            if (message.role() != MessageRole.SYSTEM) {
                appendContent(contents, mapRole(message.role()), mapParts(message, request.model().model()));
            }
        }
        if (contents.isEmpty()) {
            throw new GenericAgentProviderException(
                    ModelProvider.GOOGLE_GEMINI,
                    request.model().model(),
                    "Gemini requires at least one non-system conversation message");
        }

        if (!request.tools().isEmpty()) {
            if (Boolean.FALSE.equals(request.options().parallelToolCalls())) {
                throw new GenericAgentProviderException(
                        ModelProvider.GOOGLE_GEMINI,
                        request.model().model(),
                        "Gemini does not expose a provider setting that guarantees single function calls");
            }
            mapTools(root, request.tools());
        }
        mapGenerationOptions(root, request.options());
        return root;
    }

    BaseAgentResponse fromProviderResponse(JsonNode response, String requestedModel) {
        JsonNode candidate = response.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String blockReason = response.path("promptFeedback").path("blockReason").asText("unknown");
            throw new GenericAgentProviderException(
                    ModelProvider.GOOGLE_GEMINI,
                    requestedModel,
                    "Gemini response did not contain a candidate; block reason: " + blockReason);
        }

        String responseId = textOrNull(response.path("responseId"));
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        int callIndex = 0;
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            }
            JsonNode functionCall = part.path("functionCall");
            if (functionCall.isObject()) {
                String name = textOrNull(functionCall.path("name"));
                if (name == null || !functionCall.path("args").isObject()) {
                    throw new GenericAgentProviderException(
                            ModelProvider.GOOGLE_GEMINI,
                            requestedModel,
                            "Gemini returned an incomplete function call");
                }
                String id = textOrNull(functionCall.path("id"));
                if (id == null) {
                    id = "gemini-call-" + (responseId == null ? UUID.randomUUID() : responseId) + "-" + callIndex;
                }
                Map<String, String> providerMetadata = new LinkedHashMap<>();
                String thoughtSignature = textOrNull(part.path("thoughtSignature"));
                if (thoughtSignature != null) {
                    providerMetadata.put(THOUGHT_SIGNATURE_METADATA_KEY, thoughtSignature);
                }
                toolCalls.add(new ToolCall(id, name, functionCall.path("args"), providerMetadata));
                callIndex++;
            }
        }

        String providerFinishReason = candidate.path("finishReason").asText("");
        FinishReason finishReason = toolCalls.isEmpty()
                ? mapFinishReason(providerFinishReason)
                : FinishReason.TOOL_CALLS;
        JsonNode usage = response.path("usageMetadata");
        int inputTokens = usage.path("promptTokenCount").asInt(0);
        int outputTokens = usage.path("candidatesTokenCount").asInt(0);
        int totalTokens = Math.max(usage.path("totalTokenCount").asInt(0), inputTokens + outputTokens);

        Map<String, String> metadata = new LinkedHashMap<>();
        putText(metadata, "providerModel", response.path("modelVersion"));
        putText(metadata, "providerFinishReason", candidate.path("finishReason"));

        return new BaseAgentResponse(
                responseId,
                text.toString(),
                toolCalls,
                finishReason,
                new TokenUsage(inputTokens, outputTokens, totalTokens),
                metadata);
    }

    private void mapSystemInstruction(ObjectNode root, List<AgentMessage> messages) {
        String systemInstruction = messages.stream()
                .filter(message -> message.role() == MessageRole.SYSTEM)
                .map(AgentMessage::content)
                .filter(content -> !content.isBlank())
                .collect(Collectors.joining("\n\n"));
        if (!systemInstruction.isBlank()) {
            root.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", systemInstruction);
        }
    }

    private ArrayNode mapParts(AgentMessage message, String model) {
        ArrayNode parts = objectMapper.createArrayNode();
        if (message.role() != MessageRole.TOOL && !message.content().isBlank()) {
            parts.addObject().put("text", message.content());
        }
        if (message.role() == MessageRole.USER) {
            for (AttachmentReference attachment : message.attachments()) {
                if (attachment.mediaType() == null) {
                    throw new GenericAgentProviderException(
                            ModelProvider.GOOGLE_GEMINI,
                            model,
                            "Gemini attachment references require a media type");
                }
                ObjectNode fileData = parts.addObject().putObject("fileData");
                fileData.put("fileUri", attachment.reference());
                fileData.put("mimeType", attachment.mediaType());
            }
        }
        if (message.role() == MessageRole.ASSISTANT) {
            for (ToolCall call : message.toolCalls()) {
                ObjectNode part = parts.addObject();
                ObjectNode functionCall = part.putObject("functionCall");
                functionCall.put("id", call.id());
                functionCall.put("name", call.name());
                functionCall.set("args", call.arguments());
                String thoughtSignature = call.providerMetadata().get(THOUGHT_SIGNATURE_METADATA_KEY);
                if (thoughtSignature != null && !thoughtSignature.isBlank()) {
                    part.put("thoughtSignature", thoughtSignature);
                }
            }
        }
        if (message.role() == MessageRole.TOOL) {
            ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
            functionResponse.put("id", message.toolCallId());
            functionResponse.put("name", message.name());
            functionResponse.set("response", parseToolResult(message.content()));
        }
        if (parts.isEmpty()) {
            throw new GenericAgentProviderException(
                    ModelProvider.GOOGLE_GEMINI,
                    model,
                    "Gemini conversation messages must contain text, files, or function calls");
        }
        return parts;
    }

    private ObjectNode parseToolResult(String result) {
        try {
            JsonNode parsed = objectMapper.readTree(result);
            if (parsed != null && parsed.isObject()) {
                return (ObjectNode) parsed;
            }
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.set("result", parsed == null ? objectMapper.getNodeFactory().textNode(result) : parsed);
            return wrapped;
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode().put("result", result);
        }
    }

    private void appendContent(ArrayNode contents, String role, ArrayNode parts) {
        JsonNode previous = contents.isEmpty() ? null : contents.get(contents.size() - 1);
        if (previous != null && role.equals(previous.path("role").asText())) {
            ArrayNode previousParts = (ArrayNode) previous.path("parts");
            previousParts.addAll(parts);
            return;
        }
        ObjectNode content = contents.addObject();
        content.put("role", role);
        content.set("parts", parts);
    }

    private void mapTools(ObjectNode root, List<ToolDefinition> tools) {
        ArrayNode declarations = root.putArray("tools").addObject().putArray("functionDeclarations");
        for (ToolDefinition tool : tools) {
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", tool.name());
            declaration.put("description", tool.description());
            declaration.set("parametersJsonSchema", tool.inputSchema());
        }
    }

    private void mapGenerationOptions(ObjectNode root, GenerationOptions options) {
        ObjectNode generationConfig = objectMapper.createObjectNode();
        if (options.temperature() != null) {
            generationConfig.put("temperature", options.temperature());
        }
        if (options.maxOutputTokens() != null) {
            generationConfig.put("maxOutputTokens", options.maxOutputTokens());
        }
        if (options.responseSchema() != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.set("responseJsonSchema", options.responseSchema());
        }
        if (!generationConfig.isEmpty()) {
            root.set("generationConfig", generationConfig);
        }
    }

    private static String mapRole(MessageRole role) {
        return switch (role) {
            case USER, TOOL -> "user";
            case ASSISTANT -> "model";
            case SYSTEM -> throw new IllegalArgumentException("System messages use systemInstruction");
        };
    }

    private static FinishReason mapFinishReason(String value) {
        return switch (value) {
            case "STOP" -> FinishReason.STOP;
            case "MAX_TOKENS" -> FinishReason.MAX_TOKENS;
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
                    FinishReason.CONTENT_FILTER;
            case "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> FinishReason.ERROR;
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
