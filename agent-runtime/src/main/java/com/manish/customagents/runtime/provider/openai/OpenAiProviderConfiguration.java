package com.manish.customagents.runtime.provider.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProviderProperties.class)
public class OpenAiProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agents.llm.openai", name = "enabled", havingValue = "true")
    OpenAiLlmClient openAiLlmClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            OpenAiProviderProperties properties) {
        properties.validateEnabledConfiguration();
        RestClient restClient = restClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
        return new OpenAiLlmClient(restClient, objectMapper, properties.getModels());
    }
}
