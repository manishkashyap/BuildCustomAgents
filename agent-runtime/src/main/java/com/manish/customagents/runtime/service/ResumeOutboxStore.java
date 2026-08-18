package com.manish.customagents.runtime.service;

import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import com.manish.customagents.runtime.repository.RuntimeOutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResumeOutboxStore {
    private static final Duration ABANDONED_CLAIM_AFTER = Duration.ofMinutes(15);
    private final RuntimeOutboxEventRepository repository;
    private final Clock clock;

    public ResumeOutboxStore(RuntimeOutboxEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public List<RuntimeOutboxEventEntity> claim() {
        repository.findTop20ByStatusAndClaimedAtBeforeOrderByClaimedAtAsc(
                        "CLAIMED", clock.instant().minus(ABANDONED_CLAIM_AFTER))
                .forEach(event -> event.retry("Recovered abandoned resume claim", clock.instant()));
        List<RuntimeOutboxEventEntity> events = repository
                .findTop20ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc("PENDING", clock.instant());
        events.forEach(event -> event.claim(clock.instant()));
        repository.saveAll(events);
        return List.copyOf(events);
    }

    @Transactional
    public Optional<RuntimeOutboxEventEntity> claim(String id) {
        return repository.findForUpdate(id)
                .filter(event -> "PENDING".equals(event.getStatus()))
                .map(event -> {
                    event.claim(clock.instant());
                    return event;
                });
    }

    @Transactional(readOnly = true)
    public Optional<String> status(String id) {
        return repository.findById(id).map(RuntimeOutboxEventEntity::getStatus);
    }

    @Transactional
    public void complete(String id) {
        repository.findById(id).ifPresent(event -> event.complete(clock.instant()));
    }

    @Transactional
    public void retry(String id, String error) {
        repository.findById(id).ifPresent(event -> event.retry(
                error == null ? "resume failed" : error,
                clock.instant().plus(Duration.ofSeconds(
                        Math.min(300, 1L << Math.min(8, event.getAttemptCount()))))));
    }
}
