package com.manish.customagents.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCustomAgentRequest(
        @NotBlank @Size(min = 3, max = 120) String name,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 500) String role,
        @NotBlank @Size(max = 20_000) String instructions,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 2_000) String> rules,
        @Size(max = 5_000) String outputFormat,
        JsonNode outputSchema,
        JsonNode context,
        @NotNull @Size(max = 20) List<@Valid AgentExample> examples,
        @NotNull @Size(max = 100) Set<@NotBlank @Size(max = 150) String> allowedTools,
        JsonNode humanInteractionPolicy) {

    @AssertTrue(message = "outputSchema must be a JSON object when provided")
    @JsonIgnore
    public boolean isOutputSchemaObject() {
        return outputSchema == null || outputSchema.isObject();
    }

    @AssertTrue(message = "context must be a JSON object when provided")
    @JsonIgnore
    public boolean isContextObject() {
        return context == null || context.isObject();
    }

    @AssertTrue(message = "humanInteractionPolicy must be a JSON object when provided")
    @JsonIgnore
    public boolean isHumanInteractionPolicyObject() {
        return humanInteractionPolicy == null || humanInteractionPolicy.isObject();
    }

    @AssertTrue(message = "humanInteractionPolicy clarification configuration is invalid")
    @JsonIgnore
    public boolean isClarificationPolicyValid() {
        if (humanInteractionPolicy == null || !humanInteractionPolicy.isObject()) {
            return true;
        }
        JsonNode clarification = humanInteractionPolicy.path("clarification");
        if (clarification.isMissingNode()) {
            return true;
        }
        if (!clarification.isObject()) {
            return false;
        }
        long expiry = clarification.path("expiresAfterSeconds").asLong(86_400);
        long maximum = clarification.path("maxRequestsPerRootRun").asLong(20);
        if (expiry < 60 || expiry > 604_800 || maximum < 1 || maximum > 100) {
            return false;
        }
        if (!Set.of("FAIL_CHILD", "FAIL_ROOT", "ESCALATE", "USE_DEFAULT")
                .contains(clarification.path("onExpire").asText("FAIL_CHILD"))) {
            return false;
        }
        JsonNode audience = clarification.path("defaultAudience");
        if (audience.isMissingNode()) {
            return true;
        }
        if (!audience.isObject()) {
            return false;
        }
        String type = audience.path("type").asText("");
        if (!Set.of("RUN_REQUESTER", "ROLE", "GROUP").contains(type)) {
            return false;
        }
        JsonNode values = audience.path("values");
        if ((type.equals("ROLE") || type.equals("GROUP"))
                && (!values.isArray() || values.isEmpty())) {
            return false;
        }
        return values.isMissingNode() || values.isArray();
    }
}
