package com.manish.customagents.runtime.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DraftAgentTestRequest(
        @NotBlank @Size(max = 36) String agentId,
        @Size(max = 20_000) String task,
        @NotNull JsonNode input,
        ModelProvider provider,
        @Size(max = 150) String model,
        @Pattern(regexp = "[a-f0-9]{64}", message = "must be a lowercase SHA-256 value")
        String expectedDraftRevision,
        @Size(max = 50) Map<@NotBlank @Size(max = 150) String, @NotNull JsonNode> mockToolResults,
        @Size(max = 20) List<@Valid TestHumanResponse> humanResponses) {

    public DraftAgentTestRequest {
        Map<String, JsonNode> copiedMocks = new LinkedHashMap<>();
        if (mockToolResults != null) {
            mockToolResults.forEach((name, result) ->
                    copiedMocks.put(name == null ? null : name.strip(), result == null ? null : result.deepCopy()));
        }
        mockToolResults = Map.copyOf(copiedMocks);
        humanResponses = humanResponses == null ? List.of() : List.copyOf(humanResponses);
        expectedDraftRevision = expectedDraftRevision == null || expectedDraftRevision.isBlank()
                ? null : expectedDraftRevision.strip();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "provider and model must either both be provided or both be omitted")
    public boolean isProviderAndModelSelectionValid() {
        return (provider == null && model == null)
                || (provider != null && model != null && !model.isBlank());
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "expectedDraftRevision is required when humanResponses are supplied")
    public boolean isHumanResponseRevisionValid() {
        return humanResponses.isEmpty() || expectedDraftRevision != null;
    }

    public ModelSelection modelSelectionOr(ModelSelection defaultSelection) {
        return provider == null ? defaultSelection : new ModelSelection(provider, model);
    }
}
