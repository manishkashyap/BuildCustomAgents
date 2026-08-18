package com.manish.customagents.runtime.errors;

import com.manish.customagents.runtime.model.ModelSelection;

public class UnsupportedLlmClientException extends RuntimeException {

    public UnsupportedLlmClientException(ModelSelection model) {
        super("No LLM client supports provider %s and model %s"
                .formatted(model.provider(), model.model()));
    }
}
