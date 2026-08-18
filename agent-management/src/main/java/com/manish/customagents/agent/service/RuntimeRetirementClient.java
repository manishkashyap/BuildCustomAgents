package com.manish.customagents.agent.service;

import com.manish.customagents.agent.model.RetirementEligibilityResponse;
import com.manish.customagents.error.RuntimeRetirementCheckException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class RuntimeRetirementClient {
    private final RestClient client;
    private final String internalToken;

    public RuntimeRetirementClient(
            RestClient.Builder builder,
            @Value("${agents.runtime.base-url:http://localhost:8081}") String baseUrl,
            @Value("${agents.runtime.internal-token:local-internal-token}") String internalToken,
            @Value("${agents.runtime.connect-timeout:2s}") Duration connectTimeout,
            @Value("${agents.runtime.read-timeout:5s}") Duration readTimeout) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(requests).build();
        this.internalToken = internalToken;
    }

    public RetirementEligibilityResponse check(String licenseCode, String agentId) {
        try {
            RetirementEligibilityResponse response = client.get()
                    .uri("/internal/v1/agents/{agentId}/retirement-eligibility", agentId)
                    .header("X-Agent-License-Code", licenseCode)
                    .header("X-Agent-Internal-Token", internalToken)
                    .retrieve()
                    .body(RetirementEligibilityResponse.class);
            if (response == null) throw new IllegalStateException("Runtime returned an empty response");
            return response;
        } catch (RestClientException | IllegalStateException exception) {
            throw new RuntimeRetirementCheckException(
                    "Agent Runtime could not be consulted for retirement eligibility", exception);
        }
    }
}
