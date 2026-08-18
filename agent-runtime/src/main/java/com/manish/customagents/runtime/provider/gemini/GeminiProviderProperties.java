package com.manish.customagents.runtime.provider.gemini;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agents.llm.gemini")
public class GeminiProviderProperties {

    private boolean enabled;
    private String baseUrl = "https://generativelanguage.googleapis.com";
    private String apiKey;
    private List<String> models = new ArrayList<>(List.of("gemini-3.6-flash"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<String> getModels() {
        return List.copyOf(models);
    }

    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
    }

    void validateEnabledConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("agents.llm.gemini.api-key is required when Gemini is enabled");
        }
        validateBaseUrl(baseUrl, "agents.llm.gemini.base-url");
        if (models.isEmpty()) {
            throw new IllegalStateException("agents.llm.gemini.models must contain at least one model");
        }
    }

    private static void validateBaseUrl(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(propertyName + " must be a valid URI", exception);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalStateException(propertyName + " must be an absolute URI");
        }
    }
}
