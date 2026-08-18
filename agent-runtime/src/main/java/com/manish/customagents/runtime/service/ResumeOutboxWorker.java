package com.manish.customagents.runtime.service;

import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResumeOutboxWorker {
    private final ResumeOutboxStore store;
    private final ResumeOutboxProcessor processor;

    public ResumeOutboxWorker(
            ResumeOutboxStore store,
            ResumeOutboxProcessor processor) {
        this.store = store;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${AGENT_RUNTIME_HUMAN_INTERACTION_RESUME_POLL_MS:1000}")
    public void process() {
        for (RuntimeOutboxEventEntity event : store.claim()) {
            try {
                processor.process(event);
                store.complete(event.getId());
            } catch (Exception exception) {
                store.retry(event.getId(), exception.getMessage());
            }
        }
    }
}
