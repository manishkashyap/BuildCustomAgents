package com.manish.customagents.runtime.model;

import java.util.Objects;

public record ModelSelection(ModelProvider provider, String model) {

    public ModelSelection {
        provider = Objects.requireNonNull(provider, "provider must not be null");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        model = model.strip();
    }
}
