package com.manish.customagents.runtime.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResumeOutboxProcessor {
    private final AgentExecutionService executionService;
    private final ObjectMapper objectMapper;

    public ResumeOutboxProcessor(
            AgentExecutionService executionService, ObjectMapper objectMapper) {
        this.executionService = executionService;
        this.objectMapper = objectMapper;
    }

    public void process(RuntimeOutboxEventEntity event) throws Exception {
        JsonNode payload = objectMapper.readTree(event.getPayloadJson());
        if ("ROOT_RESUME_REQUESTED".equals(event.getEventType())) {
            List<String> interactionIds = new ArrayList<>();
            payload.path("interactionIds").forEach(value -> interactionIds.add(value.asText()));
            executionService.resumeFromInteractions(event.getAggregateId(), interactionIds);
            return;
        }
        executionService.resumeFromInteraction(
                event.getAggregateId(), payload.path("interactionId").asText());
    }
}
