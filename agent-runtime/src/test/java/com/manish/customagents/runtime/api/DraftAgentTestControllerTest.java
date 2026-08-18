package com.manish.customagents.runtime.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.model.DraftAgentTestResponse;
import com.manish.customagents.runtime.model.DraftAgentTestStatus;
import com.manish.customagents.runtime.model.TokenUsage;
import com.manish.customagents.runtime.service.DraftAgentTestService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DraftAgentTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(RuntimeApiExceptionHandler.class)
class DraftAgentTestControllerTest {

    private static final String AGENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private DraftAgentTestService testService;

    @Test
    void acceptsAValidTransientTestRequest() throws Exception {
        Instant now = Instant.parse("2026-08-14T08:00:00Z");
        when(testService.test(eq("tenant-1"), any(), eq("editor-1")))
                .thenReturn(new DraftAgentTestResponse(
                        "test-run-1", AGENT_ID, "a".repeat(64),
                        DraftAgentTestStatus.COMPLETED, "GOOGLE_GEMINI", "gemini-default",
                        objectMapper.createObjectNode().put("answer", "done"),
                        new TokenUsage(10, 2, 12), List.of(), List.of(), List.of(), now, now));

        mockMvc.perform(post("/api/v1/agent-test-runs")
                        .header("X-Agent-License-Code", "tenant-1")
                        .header("X-Agent-User-Id", "editor-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId":"%s",
                                  "task":"Test the agent",
                                  "input":{"seed":"value"}
                                }
                                """.formatted(AGENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testRunId").value("test-run-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.output.answer").value("done"));
    }

    @Test
    void requiresDraftRevisionWhenSubmittingHumanResponses() throws Exception {
        mockMvc.perform(post("/api/v1/agent-test-runs")
                        .header("X-Agent-License-Code", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId":"%s",
                                  "input":{},
                                  "humanResponses":[{
                                    "interactionToken":"token",
                                    "action":"ANSWER",
                                    "answer":"Bengaluru"
                                  }]
                                }
                                """.formatted(AGENT_ID)))
                .andExpect(status().isBadRequest());
    }
}
