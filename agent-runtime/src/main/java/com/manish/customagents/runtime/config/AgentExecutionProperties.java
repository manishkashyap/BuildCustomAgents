package com.manish.customagents.runtime.config;

import com.manish.customagents.runtime.model.ModelProvider;
import com.manish.customagents.runtime.model.ModelSelection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agents.execution")
public class AgentExecutionProperties {

    @NotNull
    private ModelProvider provider = ModelProvider.GOOGLE_GEMINI;

    @NotBlank
    private String model = "gemini-3.6-flash";

    @Min(1)
    @Max(50)
    private int maxTurns = 8;

    @Min(1)
    @Max(10)
    private int maxAgentDepth = 3;

    @Min(1)
    @Max(100)
    private int maxAgentInvocations = 10;

    public ModelProvider getProvider() {
        return provider;
    }

    public void setProvider(ModelProvider provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getMaxAgentDepth() {
        return maxAgentDepth;
    }

    public void setMaxAgentDepth(int maxAgentDepth) {
        this.maxAgentDepth = maxAgentDepth;
    }

    public int getMaxAgentInvocations() {
        return maxAgentInvocations;
    }

    public void setMaxAgentInvocations(int maxAgentInvocations) {
        this.maxAgentInvocations = maxAgentInvocations;
    }

    public ModelSelection defaultModelSelection() {
        return new ModelSelection(provider, model);
    }
}
