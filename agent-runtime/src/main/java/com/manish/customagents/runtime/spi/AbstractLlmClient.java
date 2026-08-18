package com.manish.customagents.runtime.spi;

import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.ModelProvider;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Convenience base class for LLM clients bound to one provider and model set. */
public abstract class AbstractLlmClient implements LlmClient {

    private final ModelProvider provider;
    private final Set<String> supportedModels;

    protected AbstractLlmClient(ModelProvider provider, Collection<String> supportedModels) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(supportedModels, "supportedModels must not be null");
        this.supportedModels = supportedModels.stream()
                .map(AbstractLlmClient::normalizeModel)
                .collect(Collectors.toUnmodifiableSet());
        if (this.supportedModels.isEmpty()) {
            throw new IllegalArgumentException("At least one supported model is required");
        }
    }

    @Override
    public final ModelProvider provider() {
        return provider;
    }

    @Override
    public final boolean supports(String model) {
        return model != null && supportedModels.contains(normalizeModel(model));
    }

    @Override
    public final BaseAgentResponse generate(BaseAgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.model().provider() != provider || !supports(request.model().model())) {
            throw new IllegalArgumentException(
                    "%s LLM client does not support model %s"
                            .formatted(provider, request.model().model()));
        }
        return Objects.requireNonNull(doGenerate(request), "LLM client returned a null response");
    }

    protected abstract BaseAgentResponse doGenerate(BaseAgentRequest request);

    private static String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model identifier must not be blank");
        }
        return model.strip().toLowerCase(Locale.ROOT);
    }
}
