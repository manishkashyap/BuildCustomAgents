package com.manish.customagents.tool.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.error.ApiExceptionHandler;
import com.manish.customagents.tool.enums.ToolStatus;
import com.manish.customagents.tool.model.CreateToolRequest;
import com.manish.customagents.tool.model.ToolResponse;
import com.manish.customagents.tool.model.ToolStatusResponse;
import com.manish.customagents.tool.service.CustomToolService;
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

@WebMvcTest(CustomToolController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CustomToolControllerTest {

    private static final String VALID_REQUEST = """
            {
              "name": "campaign.get",
              "description": "Gets a campaign",
              "type": "HTTP",
              "inputSchema": {"type": "object"},
              "configuration": {
                "method": "GET",
                "url": "https://api.example.com/campaigns/{campaignId}"
              },
              "executionPolicy": {"operation": "READ"}
            }
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CustomToolService toolService;

    @Test
    void createsDraftDynamicTool() throws Exception {
        CreateToolRequest definition = objectMapper.readValue(VALID_REQUEST, CreateToolRequest.class);
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        when(toolService.create(eq("tenant-1"), any())).thenReturn(new ToolResponse(
                "tool-1", "tenant-1", ToolStatus.DRAFT, 1, definition, now, now));

        mockMvc.perform(post("/api/v1/tools")
                        .header(CustomToolController.LICENSE_CODE_HEADER, "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/tools/tool-1"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.definition.type").value("HTTP"));
    }

    @Test
    void publishesDynamicTool() throws Exception {
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        when(toolService.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED))
                .thenReturn(new ToolStatusResponse(
                        "tool-1", "tenant-1", ToolStatus.PUBLISHED, 1, now));

        mockMvc.perform(patch("/api/v1/tools/{toolId}/status", "tool-1")
                        .header(CustomToolController.LICENSE_CODE_HEADER, "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void listsTenantTools() throws Exception {
        CreateToolRequest definition = objectMapper.readValue(VALID_REQUEST, CreateToolRequest.class);
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        when(toolService.list("tenant-1")).thenReturn(List.of(new ToolResponse(
                "tool-1", "tenant-1", ToolStatus.DRAFT, 1, definition, now, now)));

        mockMvc.perform(get("/api/v1/tools")
                        .header(CustomToolController.LICENSE_CODE_HEADER, "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tool-1"))
                .andExpect(jsonPath("$[0].definition.name").value("campaign.get"));
    }

    @Test
    void getsAndUpdatesADraftTool() throws Exception {
        CreateToolRequest definition = objectMapper.readValue(VALID_REQUEST, CreateToolRequest.class);
        Instant now = Instant.parse("2026-08-04T12:00:00Z");
        ToolResponse response = new ToolResponse(
                "tool-1", "tenant-1", ToolStatus.DRAFT, 1, definition, now, now);
        when(toolService.get("tenant-1", "tool-1")).thenReturn(response);
        when(toolService.updateDraft(eq("tenant-1"), eq("tool-1"), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/tools/{toolId}", "tool-1")
                        .header(CustomToolController.LICENSE_CODE_HEADER, "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.type").value("HTTP"));

        mockMvc.perform(patch("/api/v1/tools/{toolId}", "tool-1")
                        .header(CustomToolController.LICENSE_CODE_HEADER, "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
