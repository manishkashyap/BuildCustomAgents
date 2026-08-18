package com.manish.customagents.runtime.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agents.test-runs")
public class DraftAgentTestProperties {

    @NotBlank
    @Size(min = 32)
    private String interactionTokenSecret = "local-draft-test-token-secret-change-me";

    @NotNull
    private Duration interactionTokenTtl = Duration.ofMinutes(30);

    public String getInteractionTokenSecret() {
        return interactionTokenSecret;
    }

    public void setInteractionTokenSecret(String interactionTokenSecret) {
        this.interactionTokenSecret = interactionTokenSecret;
    }

    public Duration getInteractionTokenTtl() {
        return interactionTokenTtl;
    }

    public void setInteractionTokenTtl(Duration interactionTokenTtl) {
        this.interactionTokenTtl = interactionTokenTtl;
    }
}
