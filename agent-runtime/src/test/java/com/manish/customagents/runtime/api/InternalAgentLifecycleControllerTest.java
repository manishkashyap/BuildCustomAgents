package com.manish.customagents.runtime.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manish.customagents.runtime.model.RetirementEligibilityResponse;
import com.manish.customagents.runtime.service.AgentRetirementEligibilityService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalAgentLifecycleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(RuntimeApiExceptionHandler.class)
@TestPropertySource(properties = "AGENT_INTERNAL_TOKEN=test-token")
class InternalAgentLifecycleControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private AgentRetirementEligibilityService service;

    @Test
    void returnsEligibilityToAnAuthenticatedServiceCaller() throws Exception {
        when(service.check("tenant-1", "agent-1"))
                .thenReturn(new RetirementEligibilityResponse(
                        false, 1, Map.of("WAITING_FOR_HUMAN", 1L)));

        mockMvc.perform(get("/internal/v1/agents/{agentId}/retirement-eligibility", "agent-1")
                        .header("X-Agent-License-Code", "tenant-1")
                        .header("X-Agent-Internal-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.activeRunCount").value(1));
    }

    @Test
    void rejectsAnInvalidServiceToken() throws Exception {
        mockMvc.perform(get("/internal/v1/agents/{agentId}/retirement-eligibility", "agent-1")
                        .header("X-Agent-License-Code", "tenant-1")
                        .header("X-Agent-Internal-Token", "wrong"))
                .andExpect(status().isForbidden());
    }
}
