package com.manish.customagents.runtime.provider.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiProviderProperties.class)
public class GeminiProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agents.llm.gemini", name = "enabled", havingValue = "true")
    GeminiLlmClient geminiLlmClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            GeminiProviderProperties properties) {
        properties.validateEnabledConfiguration();
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("x-goog-api-key", properties.getApiKey())
                .build();
        return new GeminiLlmClient(restClient, objectMapper, properties.getModels());
    }
}
