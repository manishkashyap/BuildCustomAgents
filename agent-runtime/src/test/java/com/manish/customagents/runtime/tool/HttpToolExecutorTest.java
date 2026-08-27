package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import com.manish.customagents.runtime.definition.TenantEgressAllowlistRepository;
import com.manish.customagents.runtime.errors.AgentExecutionException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TenantEgressAllowlistRepository allowlist;

    /**
     * The tenant allowlist is the only gate exercised here; the address checks would need real DNS,
     * so they are covered separately in {@link HttpEgressGuardTest}.
     */
    private HttpEgressGuard guard(List<String> tenantPatterns) {
        DynamicHttpToolProperties properties = new DynamicHttpToolProperties();
        properties.setAllowPrivateNetworks(true);
        when(allowlist.activePatterns("tenant-1")).thenReturn(tenantPatterns);
        return new HttpEgressGuard(allowlist, properties);
    }

    @Test
    void executesPublishedHttpDefinitionAndExpandsArguments() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolExecutor executor = new HttpToolExecutor(
                builder.build(), objectMapper, guard(List.of("api.example.com")));
        server.expect(requestTo("https://api.example.com/campaigns/cmp-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("User-Agent", "Custom-Agent-Platform/1.0"))
                .andRespond(withSuccess("{\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));

        var result = executor.execute(new ToolExecutionRequest(
                definition("https://api.example.com/campaigns/{campaignId}"),
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode().put("campaignId", "cmp-1")));

        assertThat(result.output().path("status").asText()).isEqualTo("ACTIVE");
        server.verify();
    }

    @Test
    void rejectsHostsOutsideTheTenantAllowlist() {
        HttpToolExecutor executor = new HttpToolExecutor(
                RestClient.builder().build(), objectMapper, guard(List.of("api.example.com")));

        assertThatThrownBy(() -> executor.execute(new ToolExecutionRequest(
                definition("https://attacker.example/campaigns/{campaignId}"),
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode().put("campaignId", "cmp-1"))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("not on this tenant's egress allowlist");
    }

    @Test
    void appliesOneTenantsAllowlistAndNotAnothers() {
        // The whole point of moving the list out of a process-wide property: tenant-2 registering a
        // host must not let tenant-1 reach it.
        when(allowlist.activePatterns("tenant-1")).thenReturn(List.of("api.example.com"));
        when(allowlist.activePatterns("tenant-2")).thenReturn(List.of("api.other.com"));
        DynamicHttpToolProperties properties = new DynamicHttpToolProperties();
        properties.setAllowPrivateNetworks(true);
        HttpToolExecutor executor = new HttpToolExecutor(
                RestClient.builder().build(), objectMapper, new HttpEgressGuard(allowlist, properties));

        assertThatThrownBy(() -> executor.execute(new ToolExecutionRequest(
                definition("https://api.other.com/campaigns/{campaignId}"),
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode().put("campaignId", "cmp-1"))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("api.other.com");
    }

    /**
     * The URL is expanded with model-supplied arguments before it is checked, so an argument that
     * smuggles in a different authority must still be refused.
     */
    @Test
    void rejectsAnArgumentThatRedirectsTheRequestToAnotherHost() {
        HttpToolExecutor executor = new HttpToolExecutor(
                RestClient.builder().build(), objectMapper, guard(List.of("api.example.com")));

        assertThatThrownBy(() -> executor.execute(new ToolExecutionRequest(
                definition("https://{campaignId}/campaigns"),
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode().put("campaignId", "attacker.example"))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("attacker.example");
    }

    private PublishedToolDefinition definition(String url) {
        return new PublishedToolDefinition(
                "tool-1", "campaign.get", "Gets a campaign", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("method", "GET").put("url", url),
                objectMapper.createObjectNode());
    }
}
