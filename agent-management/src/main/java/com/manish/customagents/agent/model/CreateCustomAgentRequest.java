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
import com.manish.customagents.contracts.HumanInteractionPolicyRules;

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

    private static final Set<String> ON_EXPIRE_BEHAVIOURS =
            Set.of("FAIL_CHILD", "FAIL_ROOT", "ESCALATE", "USE_DEFAULT");

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
        long maximum = clarification.path("maxRequestsPerRootRun").asLong(20);
        if (!HumanInteractionPolicyRules.isExpiryInRange(clarification, "expiresAfterSeconds")
                || maximum < 1 || maximum > 100) {
            return false;
        }
        if (!ON_EXPIRE_BEHAVIOURS.contains(clarification.path("onExpire").asText("FAIL_CHILD"))) {
            return false;
        }
        return HumanInteractionPolicyRules.isAudienceValid(clarification.path("defaultAudience"));
    }
}
