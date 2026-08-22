package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import com.manish.customagents.runtime.errors.AgentExecutionException;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.manish.customagents.contracts.ToolType;

class HttpToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesPublishedHttpDefinitionAndExpandsArguments() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DynamicHttpToolProperties properties = new DynamicHttpToolProperties();
        properties.setAllowedHosts(List.of("api.example.com"));
        HttpToolExecutor executor = new HttpToolExecutor(builder, objectMapper, properties);
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
    void rejectsHostsOutsideTheRuntimeAllowlist() {
        DynamicHttpToolProperties properties = new DynamicHttpToolProperties();
        properties.setAllowedHosts(List.of("api.example.com"));
        HttpToolExecutor executor = new HttpToolExecutor(
                RestClient.builder(), objectMapper, properties);

        assertThatThrownBy(() -> executor.execute(new ToolExecutionRequest(
                definition("https://attacker.example/campaigns/{campaignId}"),
                new ToolExecutionContext("run-1", "tenant-1", "agent-1", 1),
                objectMapper.createObjectNode().put("campaignId", "cmp-1"))))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("not allowlisted");
    }

    private PublishedToolDefinition definition(String url) {
        return new PublishedToolDefinition(
                "tool-1", "campaign.get", "Gets a campaign", ToolType.HTTP, 1,
                objectMapper.createObjectNode().put("type", "object"),
                objectMapper.createObjectNode().put("method", "GET").put("url", url),
                objectMapper.createObjectNode());
    }
}
