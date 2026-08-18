package com.manish.customagents.runtime.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.provider.GenericAgentProviderException;
import com.manish.customagents.runtime.spi.AbstractLlmClient;
import java.util.Collection;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class OpenAiLlmClient extends AbstractLlmClient {

    private final RestClient restClient;
    private final OpenAiJsonMapper jsonMapper;

    OpenAiLlmClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            Collection<String> supportedModels) {
        super(ModelProvider.OPENAI, supportedModels);
        this.restClient = restClient;
        this.jsonMapper = new OpenAiJsonMapper(objectMapper);
    }

    @Override
    protected BaseAgentResponse doGenerate(BaseAgentRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.toProviderRequest(request))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new GenericAgentProviderException(
                        provider(), request.model().model(), "OpenAI returned an empty response body");
            }
            return jsonMapper.fromProviderResponse(response, request.model().model());
        } catch (RestClientResponseException exception) {
            throw new GenericAgentProviderException(
                    provider(), request.model().model(),
                    "OpenAI request failed with HTTP " + exception.getStatusCode().value(),
                    exception.getStatusCode().value(), exception);
        } catch (RestClientException exception) {
            throw new GenericAgentProviderException(
                    provider(), request.model().model(),
                    "OpenAI request failed before a response was received", null, exception);
        }
    }
}
