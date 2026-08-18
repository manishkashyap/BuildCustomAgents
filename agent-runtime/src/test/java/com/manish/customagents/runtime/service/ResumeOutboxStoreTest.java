package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.manish.customagents.runtime.entity.RuntimeOutboxEventEntity;
import com.manish.customagents.runtime.repository.RuntimeOutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeOutboxStoreTest {
    @Test
    void recoversAnAbandonedClaimBeforeClaimingPendingWork() {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        RuntimeOutboxEventRepository repository = mock(RuntimeOutboxEventRepository.class);
        RuntimeOutboxEventEntity event = new RuntimeOutboxEventEntity(
                "event-1", "root-1", "ROOT_RESUME_REQUESTED", "{}", now.minusSeconds(1000));
        event.claim(now.minusSeconds(1000));
        when(repository.findTop20ByStatusAndClaimedAtBeforeOrderByClaimedAtAsc(
                eq("CLAIMED"), eq(now.minusSeconds(900)))).thenReturn(List.of(event));
        when(repository.findTop20ByStatusAndAvailableAtBeforeOrderByCreatedAtAsc("PENDING", now))
                .thenReturn(List.of(event));
        ResumeOutboxStore store = new ResumeOutboxStore(
                repository, Clock.fixed(now, ZoneOffset.UTC));

        List<RuntimeOutboxEventEntity> claimed = store.claim();

        assertThat(claimed).containsExactly(event);
        assertThat(event.getStatus()).isEqualTo("CLAIMED");
        assertThat(event.getAttemptCount()).isEqualTo(2);
    }
}
