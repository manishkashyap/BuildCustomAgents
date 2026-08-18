package com.manish.customagents.runtime.provider;

import com.manish.customagents.runtime.model.ModelProvider;

public class GenericAgentProviderException extends RuntimeException {

    private final ModelProvider provider;
    private final String model;
    private final Integer httpStatus;

    public GenericAgentProviderException(
            ModelProvider provider,
            String model,
            String message,
            Integer httpStatus,
            Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.httpStatus = httpStatus;
    }

    public GenericAgentProviderException(
            ModelProvider provider,
            String model,
            String message) {
        this(provider, model, message, null, null);
    }

    public ModelProvider provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
