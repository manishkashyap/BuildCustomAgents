package com.manish.customagents.runtime.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpToolClientConfiguration {

    /**
     * The client HTTP tools execute through.
     *
     * <p>Redirects are never followed. The egress guard vets the URL the tool was configured with;
     * a 302 to an internal address would otherwise walk straight past that check, since the
     * redirected request is issued by the HTTP client and never re-enters the guard.
     */
    @Bean("httpToolRestClient")
    public RestClient httpToolRestClient(RestClient.Builder builder) {
        return builder.clone()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()))
                .build();
    }
}
