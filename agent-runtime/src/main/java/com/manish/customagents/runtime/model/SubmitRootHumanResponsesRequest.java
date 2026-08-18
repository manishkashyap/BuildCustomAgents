package com.manish.customagents.runtime.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitRootHumanResponsesRequest(
        @NotEmpty @Size(max = 20) List<@Valid RootHumanResponseItem> responses) {

    public SubmitRootHumanResponsesRequest {
        responses = responses == null ? List.of() : List.copyOf(responses);
    }
}
