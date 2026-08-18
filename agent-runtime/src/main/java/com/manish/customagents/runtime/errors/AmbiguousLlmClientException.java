package com.manish.customagents.runtime.errors;

import com.manish.customagents.runtime.model.ModelSelection;

public class AmbiguousLlmClientException extends RuntimeException {

    public AmbiguousLlmClientException(ModelSelection model, int clientCount) {
        super("Found %d LLM clients for provider %s and model %s"
                .formatted(clientCount, model.provider(), model.model()));
    }
}
