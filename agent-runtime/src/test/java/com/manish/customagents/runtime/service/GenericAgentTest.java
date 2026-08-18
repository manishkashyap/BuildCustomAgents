package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.manish.customagents.runtime.registry.LlmClientRegistry;
import com.manish.customagents.runtime.spi.LlmClient;
import com.manish.customagents.runtime.model.AgentMessage;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.GenerationOptions;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;
import com.manish.customagents.runtime.model.TokenUsage;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericAgentTest {

    @Test
    void delegatesTheProviderNeutralRequestToTheResolvedLlmClient() {
        LlmClientRegistry registry = mock(LlmClientRegistry.class);
        LlmClient client = mock(LlmClient.class);
        ModelSelection model = new ModelSelection(ModelProvider.OPENAI, "gpt-model");
        BaseAgentRequest request = new BaseAgentRequest(
                model, List.of(AgentMessage.user("Create a strategy")), List.of(),
                GenerationOptions.defaults(), Map.of());
        BaseAgentResponse expected = new BaseAgentResponse(
                "response-1", "done", List.of(), FinishReason.STOP,
                new TokenUsage(5, 2, 7), Map.of());
        when(registry.resolve(model)).thenReturn(client);
        when(client.generate(request)).thenReturn(expected);

        BaseAgentResponse actual = new BaseAgent(registry).generate(request);

        assertThat(actual).isSameAs(expected);
        verify(registry).resolve(model);
        verify(client).generate(request);
    }
}
