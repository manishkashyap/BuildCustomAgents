package com.manish.customagents.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.agent.enums.AgentStatus;
import com.manish.customagents.agent.model.AgentStatusResponse;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.agent.model.CustomAgentResponse;
import com.manish.customagents.agent.service.CustomAgentService;
import com.manish.customagents.error.ApiExceptionHandler;
import com.manish.customagents.error.InvalidAgentStatusTransitionException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.manish.customagents.contracts.AgentApiHeaders;

@WebMvcTest(CustomAgentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CustomAgentControllerTest {

    private static final String VALID_REQUEST = """
            {
              "name": "Campaign QA Agent",
              "description": "Checks campaign readiness",
              "role": "platform campaign quality analyst",
              "instructions": "Review the supplied campaign and return actionable findings.",
              "rules": ["Never mutate a campaign without approval"],
              "outputFormat": "Return JSON matching the output schema",
              "outputSchema": {"type": "object", "required": ["findings"]},
              "context": {"secretRefs": ["vault://custom-agents/core-api"]},
              "examples": [],
              "allowedTools": ["campaign.get"]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomAgentService customAgentService;

    @Test
    void createsDraftAgentAndReturnsLocation() throws Exception {
        CreateCustomAgentRequest request = objectMapper.readValue(VALID_REQUEST, CreateCustomAgentRequest.class);
        Instant timestamp = Instant.parse("2026-07-31T12:00:00Z");
        when(customAgentService.create(eq("account-123"), any(CreateCustomAgentRequest.class),
                eq("user-1"), eq(null)))
                .thenReturn(new CustomAgentResponse(
                        "d272ef82-c734-4873-9346-b4d250f8bf43",
                        "account-123",
                        AgentStatus.DRAFT,
                        1,
                        request,
                        timestamp,
                        timestamp));

        mockMvc.perform(post("/api/v1/agents")
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_EDITOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        "http://localhost/api/v1/agents/d272ef82-c734-4873-9346-b4d250f8bf43"))
                .andExpect(jsonPath("$.id").value("d272ef82-c734-4873-9346-b4d250f8bf43"))
                .andExpect(jsonPath("$.licenseCode").value("account-123"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.definition.name").value("Campaign QA Agent"))
                .andExpect(jsonPath("$.definition.inputSchema").doesNotExist());
    }

    @Test
    void rejectsInvalidDefinition() throws Exception {
        String invalidRequest = """
                {
                  "name": "QA",
                  "role": "analyst",
                  "rules": [],
                  "examples": [],
                  "allowedTools": []
                }
                """;

        mockMvc.perform(post("/api/v1/agents")
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_EDITOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid custom agent definition"))
                .andExpect(jsonPath("$.violations").isArray());

        verifyNoInteractions(customAgentService);
    }

    @Test
    void publishesDraftAgent() throws Exception {
        String agentId = "d272ef82-c734-4873-9346-b4d250f8bf43";
        Instant updatedAt = Instant.parse("2026-08-03T12:00:00Z");
        when(customAgentService.updateStatus(
                "account-123", agentId, AgentStatus.PUBLISHED, "user-1", null))
                .thenReturn(new AgentStatusResponse(
                        agentId,
                        "account-123",
                        AgentStatus.PUBLISHED,
                        1,
                        updatedAt));

        mockMvc.perform(patch("/api/v1/agents/{agentId}/status", agentId)
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_PUBLISHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId))
                .andExpect(jsonPath("$.licenseCode").value("account-123"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").value("2026-08-03T12:00:00Z"));
    }

    @Test
    void returnsConflictForInvalidStatusTransition() throws Exception {
        String agentId = "d272ef82-c734-4873-9346-b4d250f8bf43";
        when(customAgentService.updateStatus(
                "account-123", agentId, AgentStatus.PUBLISHED, "user-1", null))
                .thenThrow(new InvalidAgentStatusTransitionException(
                        AgentStatus.RETIRED, AgentStatus.PUBLISHED));

        mockMvc.perform(patch("/api/v1/agents/{agentId}/status", agentId)
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_PUBLISHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Invalid agent status transition"))
                .andExpect(jsonPath("$.detail").value(
                        "Agent status cannot transition from RETIRED to PUBLISHED"));
    }

    @Test
    void rejectsStatusRequestWithoutAStatus() throws Exception {
        mockMvc.perform(patch(
                        "/api/v1/agents/{agentId}/status",
                        "d272ef82-c734-4873-9346-b4d250f8bf43")
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_PUBLISHER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid custom agent definition"));

        verifyNoInteractions(customAgentService);
    }

    @Test
    void getsTheCompleteAgentDefinition() throws Exception {
        String agentId = "d272ef82-c734-4873-9346-b4d250f8bf43";
        CreateCustomAgentRequest request = objectMapper.readValue(VALID_REQUEST, CreateCustomAgentRequest.class);
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        when(customAgentService.get("account-123", agentId)).thenReturn(new CustomAgentResponse(
                agentId, "account-123", AgentStatus.DRAFT, 1, request, now, now));

        mockMvc.perform(get("/api/v1/agents/{agentId}", agentId)
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.role").value("platform campaign quality analyst"));
    }

    @Test
    void listsTenantAgents() throws Exception {
        CreateCustomAgentRequest request = objectMapper.readValue(VALID_REQUEST, CreateCustomAgentRequest.class);
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        when(customAgentService.list("account-123")).thenReturn(List.of(new CustomAgentResponse(
                "agent-1", "account-123", AgentStatus.DRAFT, 1, request, now, now)));

        mockMvc.perform(get("/api/v1/agents")
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("agent-1"))
                .andExpect(jsonPath("$[0].definition.name").value("Campaign QA Agent"));
    }

    @Test
    void partiallyUpdatesADraft() throws Exception {
        String agentId = "d272ef82-c734-4873-9346-b4d250f8bf43";
        CreateCustomAgentRequest request = objectMapper.readValue(VALID_REQUEST, CreateCustomAgentRequest.class);
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        when(customAgentService.updateDraft(eq("account-123"), eq(agentId), any(),
                eq("user-1"), eq("Tune prompt"))).thenReturn(new CustomAgentResponse(
                agentId, "account-123", AgentStatus.DRAFT, 1, request, now, now));

        mockMvc.perform(patch("/api/v1/agents/{agentId}", agentId)
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "AGENT_EDITOR")
                        .header(CustomAgentController.CHANGE_REASON_HEADER, "Tune prompt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instructions\":\"Use the revised prompt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void requiresEditorRoleForDraftUpdates() throws Exception {
        mockMvc.perform(patch("/api/v1/agents/{agentId}",
                        "d272ef82-c734-4873-9346-b4d250f8bf43")
                        .header(AgentApiHeaders.LICENSE_CODE, "account-123")
                        .header(AgentApiHeaders.USER_ID, "user-1")
                        .header(CustomAgentController.ROLES_HEADER, "RUN_REQUESTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"Analyst\"}"))
                .andExpect(status().isForbidden());
    }
}
