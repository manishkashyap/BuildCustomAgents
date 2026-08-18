package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RunAgentRequest(
        @NotBlank @Size(max = 36) String agentId,
        @NotBlank @Size(max = 20_000) String task,
        JsonNode input,
        ModelProvider provider,
        @Size(max = 150) String model) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "provider and model must either both be provided or both be omitted")
    public boolean isProviderAndModelSelectionValid() {
        return (provider == null && model == null)
                || (provider != null && model != null && !model.isBlank());
    }

    public ModelSelection modelSelectionOr(ModelSelection defaultSelection) {
        return provider == null
                ? defaultSelection
                : new ModelSelection(provider, model);
    }
}
