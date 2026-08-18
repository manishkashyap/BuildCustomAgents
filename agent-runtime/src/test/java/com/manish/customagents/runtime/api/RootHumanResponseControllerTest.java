package com.manish.customagents.runtime.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.enums.HumanInteractionStatus;
import com.manish.customagents.runtime.enums.HumanResponseAction;
import com.manish.customagents.runtime.model.AcceptedHumanResponse;
import com.manish.customagents.runtime.model.RootHumanResponsesResponse;
import com.manish.customagents.runtime.model.SubmitRootHumanResponsesRequest;
import com.manish.customagents.runtime.service.RootHumanResponseService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RootHumanResponseController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(RuntimeApiExceptionHandler.class)
class RootHumanResponseControllerTest {
    private static final String ROOT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String INTERACTION_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private RootHumanResponseService service;

    @Test
    void acceptsTheRootScopedArrayContract() throws Exception {
        Instant now = Instant.parse("2026-08-12T08:00:00Z");
        when(service.respond(
                eq("tenant-1"), eq(ROOT_ID), eq("batch-key"), eq("user-1"),
                eq(Set.of("RUN_REQUESTER")), any(SubmitRootHumanResponsesRequest.class)))
                .thenReturn(new RootHumanResponsesResponse(
                        "batch-1", ROOT_ID, AgentRunStatus.WAITING_FOR_HUMAN,
                        List.of(new AcceptedHumanResponse(
                                INTERACTION_ID, "child-1", HumanInteractionStatus.ANSWERED,
                                HumanResponseAction.ANSWER, "user-1", now)),
                        1, List.of(), now));

        mockMvc.perform(post("/api/v1/agent-runs/{rootRunId}/human-responses", ROOT_ID)
                        .header("X-Agent-License-Code", "tenant-1")
                        .header("X-Agent-User-Id", "user-1")
                        .header("X-Agent-Roles", "RUN_REQUESTER")
                        .header("Idempotency-Key", "batch-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"responses":[{
                                  "interactionId":"%s",
                                  "action":"ANSWER",
                                  "answer":"Bengaluru"
                                }]}
                                """.formatted(INTERACTION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.acceptedResponses[0].interactionId").value(INTERACTION_ID))
                .andExpect(jsonPath("$.pendingInteractionCount").value(1))
                .andExpect(jsonPath("$.resumeStatus").doesNotExist());
    }

    @Test
    void rejectsAnEmptyResponseArray() throws Exception {
        mockMvc.perform(post("/api/v1/agent-runs/{rootRunId}/human-responses", ROOT_ID)
                        .header("X-Agent-License-Code", "tenant-1")
                        .header("X-Agent-User-Id", "user-1")
                        .header("Idempotency-Key", "batch-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responses\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
