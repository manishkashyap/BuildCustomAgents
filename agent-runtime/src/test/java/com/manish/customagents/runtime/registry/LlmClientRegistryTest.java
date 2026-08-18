package com.manish.customagents.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.manish.customagents.runtime.errors.AmbiguousLlmClientException;
import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.errors.UnsupportedLlmClientException;
import com.manish.customagents.runtime.model.FinishReason;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.spi.AbstractLlmClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmClientRegistryTest {

    @Test
    void resolvesTheClientSupportingTheSelectedProviderAndModel() {
        StubLlmClient openAi = new StubLlmClient(ModelProvider.OPENAI, List.of("gpt-model"));
        StubLlmClient gemini = new StubLlmClient(
                ModelProvider.GOOGLE_GEMINI, List.of("gemini-model"));
        LlmClientRegistry registry = new LlmClientRegistry(List.of(openAi, gemini));

        assertThat(registry.resolve(new ModelSelection(
                ModelProvider.GOOGLE_GEMINI, "gemini-model"))).isSameAs(gemini);
    }

    @Test
    void modelMatchingIsCaseInsensitive() {
        StubLlmClient client = new StubLlmClient(
                ModelProvider.ANTHROPIC_CLAUDE, List.of("Claude-Model"));
        LlmClientRegistry registry = new LlmClientRegistry(List.of(client));

        assertThat(registry.resolve(new ModelSelection(
                ModelProvider.ANTHROPIC_CLAUDE, "claude-model"))).isSameAs(client);
    }

    @Test
    void rejectsUnsupportedProviderAndModelCombination() {
        LlmClientRegistry registry = new LlmClientRegistry(List.of());
        ModelSelection selection = new ModelSelection(ModelProvider.OPEN_SOURCE, "llama-model");

        assertThatThrownBy(() -> registry.resolve(selection))
                .isInstanceOf(UnsupportedLlmClientException.class)
                .hasMessageContaining("llama-model");
    }

    @Test
    void rejectsAmbiguousClients() {
        StubLlmClient first = new StubLlmClient(ModelProvider.OPENAI, List.of("gpt-model"));
        StubLlmClient second = new StubLlmClient(ModelProvider.OPENAI, List.of("gpt-model"));
        LlmClientRegistry registry = new LlmClientRegistry(List.of(first, second));

        assertThatThrownBy(() -> registry.resolve(
                new ModelSelection(ModelProvider.OPENAI, "gpt-model")))
                .isInstanceOf(AmbiguousLlmClientException.class)
                .hasMessageContaining("2 LLM clients");
    }

    private static final class StubLlmClient extends AbstractLlmClient {

        private StubLlmClient(ModelProvider provider, List<String> supportedModels) {
            super(provider, supportedModels);
        }

        @Override
        protected BaseAgentResponse doGenerate(BaseAgentRequest request) {
            return new BaseAgentResponse(
                    "response-123", "result", List.of(), FinishReason.STOP,
                    new TokenUsage(10, 5, 15), Map.of());
        }
    }
}
