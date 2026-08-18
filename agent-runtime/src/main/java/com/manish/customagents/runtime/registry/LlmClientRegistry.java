package com.manish.customagents.runtime.registry;

import com.manish.customagents.runtime.errors.AmbiguousLlmClientException;
import com.manish.customagents.runtime.spi.LlmClient;
import com.manish.customagents.runtime.errors.UnsupportedLlmClientException;
import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Discovers and routes to the single LLM client supporting a provider/model selection. */
@Component
public class LlmClientRegistry {

    private final Map<ModelProvider, List<LlmClient>> clientsByProvider;

    public LlmClientRegistry(List<LlmClient> clients) {
        Objects.requireNonNull(clients, "clients must not be null");
        EnumMap<ModelProvider, List<LlmClient>> grouped = clients.stream()
                .collect(Collectors.groupingBy(
                        LlmClient::provider,
                        () -> new EnumMap<>(ModelProvider.class),
                        Collectors.toUnmodifiableList()));
        this.clientsByProvider = Map.copyOf(grouped);
    }

    public LlmClient resolve(ModelSelection model) {
        Objects.requireNonNull(model, "model must not be null");
        List<LlmClient> matches = clientsByProvider
                .getOrDefault(model.provider(), List.of())
                .stream()
                .filter(client -> client.supports(model.model()))
                .toList();

        if (matches.isEmpty()) {
            throw new UnsupportedLlmClientException(model);
        }
        if (matches.size() > 1) {
            throw new AmbiguousLlmClientException(model, matches.size());
        }
        return matches.getFirst();
    }
}
