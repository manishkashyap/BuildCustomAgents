package com.manish.customagents.runtime.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.provider.GenericAgentProviderException;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.AttachmentReference;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.GenerationOptions;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;
import com.manish.customagents.runtime.model.ToolCall;
import com.manish.customagents.runtime.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiJsonMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiJsonMapper mapper = new OpenAiJsonMapper(objectMapper);

    @Test
    void mapsConversationToolsAndStructuredOutputToChatCompletions() {
        ToolCall call = new ToolCall(
                "call-123",
                "campaign_get",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"));
        JsonNode toolSchema = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", objectMapper.createObjectNode()
                        .set("campaignId", objectMapper.createObjectNode().put("type", "string")));
        JsonNode outputSchema = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", objectMapper.createObjectNode()
                        .set("answer", objectMapper.createObjectNode().put("type", "string")));
        BaseAgentRequest request = new BaseAgentRequest(
                new ModelSelection(ModelProvider.OPENAI, "gpt-test"),
                List.of(
                        AgentMessage.system("You are a marketing expert."),
                        AgentMessage.user("Review campaign cmp-1"),
                        AgentMessage.assistant("", List.of(call)),
                        AgentMessage.toolResult("call-123", "campaign_get", "{\"status\":\"ACTIVE\"}")),
                List.of(new ToolDefinition("campaign_get", "Gets a campaign", toolSchema)),
                new GenerationOptions(0.2, 500, false, outputSchema),
                Map.of());

        JsonNode payload = mapper.toProviderRequest(request);

        assertThat(payload.path("model").asText()).isEqualTo("gpt-test");
        assertThat(payload.path("store").asBoolean()).isFalse();
        assertThat(payload.path("messages").path(2).path("tool_calls").path(0)
                .path("function").path("name").asText()).isEqualTo("campaign_get");
        assertThat(payload.path("messages").path(3).path("tool_call_id").asText())
                .isEqualTo("call-123");
        assertThat(payload.path("tools").path(0).path("function").path("parameters"))
                .isEqualTo(toolSchema);
        assertThat(payload.path("max_completion_tokens").asInt()).isEqualTo(500);
        assertThat(payload.path("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(payload.path("response_format").path("json_schema").path("schema"))
                .isEqualTo(outputSchema);
    }

    @Test
    void mapsOpenAiFunctionCallsAndUsageToTheGenericResponse() throws Exception {
        JsonNode providerResponse = objectMapper.readTree("""
                {
                  "id": "chatcmpl-123",
                  "model": "gpt-test-2026-08-01",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-123",
                        "type": "function",
                        "function": {
                          "name": "campaign_get",
                          "arguments": "{\\\"campaignId\\\":\\\"cmp-1\\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 8,
                    "total_tokens": 28
                  }
                }
                """);

        BaseAgentResponse response = mapper.fromProviderResponse(providerResponse, "gpt-test");

        assertThat(response.responseId()).isEqualTo("chatcmpl-123");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-123");
            assertThat(call.name()).isEqualTo("campaign_get");
            assertThat(call.arguments().path("campaignId").asText()).isEqualTo("cmp-1");
        });
        assertThat(response.usage().totalTokens()).isEqualTo(28);
        assertThat(response.metadata()).containsEntry("providerModel", "gpt-test-2026-08-01");
    }

    @Test
    void rejectsAttachmentReferencesInsteadOfSilentlyDroppingThem() {
        BaseAgentRequest request = new BaseAgentRequest(
                new ModelSelection(ModelProvider.OPENAI, "gpt-test"),
                List.of(AgentMessage.user(
                        "Review this file",
                        List.of(new AttachmentReference("file-123", "application/pdf", "brief.pdf")))),
                List.of(),
                GenerationOptions.defaults(),
                Map.of());

        assertThatThrownBy(() -> mapper.toProviderRequest(request))
                .isInstanceOf(GenericAgentProviderException.class)
                .hasMessageContaining("does not support attachment");
    }
}
