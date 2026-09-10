package com.manish.customagents.runtime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.auth.AppliedAuth;
import com.manish.customagents.runtime.auth.ToolAuthApplier;
import com.manish.customagents.runtime.errors.AgentExecutionException;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import com.manish.customagents.contracts.ToolType;

/** Executes published HTTP tool definitions without creating a Spring bean per tool. */
@Component
public class HttpToolExecutor implements ToolExecutor {

    private static final String USER_AGENT = "Custom-Agent-Platform/1.0";
    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HttpEgressGuard egressGuard;
    private final ToolAuthApplier authApplier;

    public HttpToolExecutor(
            @Qualifier("httpToolRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            HttpEgressGuard egressGuard,
            ToolAuthApplier authApplier) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.egressGuard = egressGuard;
        this.authApplier = authApplier;
    }

    @Override
    public ToolType type() {
        return ToolType.HTTP;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest executionRequest) {
        PublishedToolDefinition tool = executionRequest.tool();
        JsonNode configuration = tool.configuration();
        String licenseCode = executionRequest.context().licenseCode();
        HttpMethod method = parseMethod(configuration.path("method").asText());
        URI uri = expandAndValidateUri(
                configuration.path("url").asText(), executionRequest.arguments(), licenseCode);
        // Credentials are attached only after the egress guard has vetted the destination, so a
        // secret can never be sent to a host the tenant has not approved.
        AppliedAuth auth = authApplier.apply(uri, configuration, licenseCode);
        try {
            return send(method, auth, executionRequest);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden exception) {
            // A cached token can expire between the check and the call. Exchange once and retry;
            // a static key that was rejected will not improve, and reports the original failure.
            if (!authApplier.retryableAfterUnauthorized(configuration, licenseCode)) {
                throw new AgentExecutionException(
                        "HTTP tool " + tool.name() + " was rejected by " + uri.getHost()
                                + " with " + exception.getStatusCode().value(), exception);
            }
            return send(method, authApplier.apply(uri, configuration, licenseCode), executionRequest);
        } catch (RestClientException exception) {
            throw new AgentExecutionException(
                    "HTTP tool " + tool.name() + " failed calling " + uri.getHost(), exception);
        }
    }

    private ToolExecutionResult send(
            HttpMethod method, AppliedAuth auth, ToolExecutionRequest executionRequest) {
        RestClient.RequestBodySpec request = restClient.method(method)
                .uri(auth.uri())
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .accept(MediaType.APPLICATION_JSON);
        auth.headers().forEach(request::header);
        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            request.contentType(MediaType.APPLICATION_JSON).body(executionRequest.arguments());
        }
        JsonNode response = request.retrieve().body(JsonNode.class);
        return ToolExecutionResult.of(response == null ? objectMapper.nullNode() : response);
    }

    private HttpMethod parseMethod(String configuredMethod) {
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(configuredMethod.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AgentExecutionException("HTTP tool has an unsupported method: " + configuredMethod);
        }
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new AgentExecutionException("HTTP tool has an unsupported method: " + configuredMethod);
        }
        return method;
    }

    private URI expandAndValidateUri(String template, JsonNode arguments, String licenseCode) {
        if (template == null || template.isBlank()) {
            throw new AgentExecutionException("HTTP tool configuration.url must not be blank");
        }
        Map<String, Object> variables = new HashMap<>();
        arguments.properties().forEach(entry -> variables.put(
                entry.getKey(), entry.getValue().isValueNode()
                        ? entry.getValue().asText()
                        : entry.getValue().toString()));
        final URI uri;
        try {
            uri = UriComponentsBuilder.fromUriString(template)
                    .buildAndExpand(variables)
                    .encode()
                    .toUri();
        } catch (IllegalArgumentException exception) {
            throw new AgentExecutionException("Unable to expand HTTP tool URL template", exception);
        }
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new AgentExecutionException("HTTP tool URL must be an absolute http/https URL without user info");
        }
        // Checked after expansion, because the template is filled in with model-supplied arguments:
        // the host that will actually be contacted is only known here.
        egressGuard.check(uri, licenseCode);
        return uri;
    }
}
