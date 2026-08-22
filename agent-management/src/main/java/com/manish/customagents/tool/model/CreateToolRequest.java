package com.manish.customagents.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.manish.customagents.contracts.ToolType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import com.manish.customagents.contracts.HumanInteractionPolicyRules;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateToolRequest(
        @NotBlank
        @Size(min = 3, max = 150)
        @Pattern(
                regexp = "[A-Za-z][A-Za-z0-9._-]*",
                message = "must start with a letter and contain only letters, numbers, dots, underscores, or hyphens")
        String name,
        @NotBlank @Size(max = 1000) String description,
        @NotNull ToolType type,
        @NotNull JsonNode inputSchema,
        @NotNull JsonNode configuration,
        JsonNode executionPolicy) {

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> OPERATIONS =
            Set.of("READ", "WRITE", "EXTERNAL_COMMUNICATION", "SENSITIVE_DATA", "DESTRUCTIVE");
    private static final Set<String> RISK_LEVELS =
            Set.of("LOW", "MEDIUM", "HIGH", "DESTRUCTIVE");
    private static final Set<String> REJECTION_BEHAVIOURS = Set.of("RETURN_TO_AGENT", "FAIL_RUN");
    private static final Set<String> EXPIRY_BEHAVIOURS = Set.of("REJECT", "FAIL_RUN", "ESCALATE");

    @AssertTrue(message = "inputSchema must be a JSON object")
    @JsonIgnore
    public boolean isInputSchemaObject() {
        return inputSchema == null || inputSchema.isObject();
    }

    @AssertTrue(message = "configuration must be a JSON object")
    @JsonIgnore
    public boolean isConfigurationObject() {
        return configuration == null || configuration.isObject();
    }

    @AssertTrue(message = "executionPolicy must be a JSON object when provided")
    @JsonIgnore
    public boolean isExecutionPolicyObject() {
        return executionPolicy == null || executionPolicy.isObject();
    }

    @AssertTrue(message = "executionPolicy approval configuration is invalid")
    @JsonIgnore
    public boolean isApprovalPolicyValid() {
        if (executionPolicy == null || !executionPolicy.isObject()) {
            return true;
        }
        String operation = executionPolicy.path("operation").asText("");
        String riskLevel = executionPolicy.path("riskLevel").asText("");
        if ((!operation.isEmpty() && !OPERATIONS.contains(operation))
                || (!riskLevel.isEmpty() && !RISK_LEVELS.contains(riskLevel))) {
            return false;
        }
        JsonNode approval = executionPolicy.path("approval");
        if (approval.isMissingNode()) {
            return true;
        }
        if (!approval.isObject()) {
            return false;
        }
        JsonNode required = approval.path("required");
        if (!required.isMissingNode() && !required.isBoolean()) {
            return false;
        }
        JsonNode allowSelfApproval = approval.path("allowSelfApproval");
        if (!allowSelfApproval.isMissingNode() && !allowSelfApproval.isBoolean()) {
            return false;
        }
        if (!HumanInteractionPolicyRules.isExpiryInRange(approval, "expiresAfterSeconds")
                || !REJECTION_BEHAVIOURS.contains(approval.path("onReject").asText("RETURN_TO_AGENT"))
                || !EXPIRY_BEHAVIOURS.contains(approval.path("onExpire").asText("REJECT"))) {
            return false;
        }
        return HumanInteractionPolicyRules.isAudienceValid(approval.path("audience"));
    }

    @AssertTrue(message = "HTTP tools require an absolute http/https configuration.url and a supported configuration.method")
    @JsonIgnore
    public boolean isHttpConfigurationValid() {
        if (type == null || type != ToolType.HTTP || configuration == null || !configuration.isObject()) {
            return true;
        }
        String url = configuration.path("url").asText("");
        String method = configuration.path("method").asText("").toUpperCase();
        return (url.startsWith("http://") || url.startsWith("https://"))
                && HTTP_METHODS.contains(method);
    }
}
