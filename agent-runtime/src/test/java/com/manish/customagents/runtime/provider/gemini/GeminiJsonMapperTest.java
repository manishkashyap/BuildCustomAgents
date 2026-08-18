package com.manish.customagents.runtime.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class GeminiJsonMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeminiJsonMapper mapper = new GeminiJsonMapper(objectMapper);

    @Test
    void mapsFilesToolsFunctionResultsAndThoughtSignatures() {
        ToolCall call = new ToolCall(
                "gemini-call-123",
                "campaign_get",
                objectMapper.createObjectNode().put("campaignId", "cmp-1"),
                Map.of(GeminiJsonMapper.THOUGHT_SIGNATURE_METADATA_KEY, "signature-123"));
        JsonNode toolSchema = objectMapper.createObjectNode()
                .put("type", "object")
                .set("properties", objectMapper.createObjectNode()
                        .set("campaignId", objectMapper.createObjectNode().put("type", "string")));
        BaseAgentRequest request = new BaseAgentRequest(
                new ModelSelection(ModelProvider.GOOGLE_GEMINI, "gemini-test"),
                List.of(
                        AgentMessage.system("You are a marketing expert."),
                        AgentMessage.user(
                                "Review this campaign brief",
                                List.of(new AttachmentReference(
                                        "https://files.example/brief.pdf",
                                        "application/pdf",
                                        "brief.pdf"))),
                        AgentMessage.assistant("", List.of(call)),
                        AgentMessage.toolResult(
                                "gemini-call-123",
                                "campaign_get",
                                "{\"status\":\"ACTIVE\"}")),
                List.of(new ToolDefinition("campaign_get", "Gets a campaign", toolSchema)),
                new GenerationOptions(0.3, 700, true, null),
                Map.of());

        JsonNode payload = mapper.toProviderRequest(request);

        assertThat(payload.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("You are a marketing expert.");
        assertThat(payload.path("contents").path(0).path("parts").path(1)
                .path("fileData").path("fileUri").asText())
                .isEqualTo("https://files.example/brief.pdf");
        assertThat(payload.path("contents").path(1).path("parts").path(0)
                .path("functionCall").path("id").asText()).isEqualTo("gemini-call-123");
        assertThat(payload.path("contents").path(1).path("parts").path(0)
                .path("thoughtSignature").asText()).isEqualTo("signature-123");
        assertThat(payload.path("contents").path(2).path("parts").path(0)
                .path("functionResponse").path("id").asText()).isEqualTo("gemini-call-123");
        assertThat(payload.path("tools").path(0).path("functionDeclarations").path(0)
                .path("parametersJsonSchema")).isEqualTo(toolSchema);
        assertThat(payload.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(700);
    }

    @Test
    void mapsGeminiTextFunctionCallsUsageAndSignatureToTheGenericResponse() throws Exception {
        JsonNode providerResponse = objectMapper.readTree("""
                {
                  "responseId": "response-123",
                  "modelVersion": "gemini-test-001",
                  "candidates": [{
                    "content": {
                      "role": "model",
                      "parts": [
                        {"text": "I will inspect the campaign."},
                        {
                          "functionCall": {
                            "id": "gemini-call-123",
                            "name": "campaign_get",
                            "args": {"campaignId": "cmp-1"}
                          },
                          "thoughtSignature": "signature-123"
                        }
                      ]
                    },
                    "finishReason": "STOP"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": 30,
                    "candidatesTokenCount": 10,
                    "totalTokenCount": 40
                  }
                }
                """);

        BaseAgentResponse response = mapper.fromProviderResponse(providerResponse, "gemini-test");

        assertThat(response.responseId()).isEqualTo("response-123");
        assertThat(response.text()).isEqualTo("I will inspect the campaign.");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("gemini-call-123");
            assertThat(call.arguments().path("campaignId").asText()).isEqualTo("cmp-1");
            assertThat(call.providerMetadata())
                    .containsEntry(GeminiJsonMapper.THOUGHT_SIGNATURE_METADATA_KEY, "signature-123");
        });
        assertThat(response.usage().totalTokens()).isEqualTo(40);
        assertThat(response.metadata()).containsEntry("providerModel", "gemini-test-001");
    }
}
