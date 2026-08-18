package com.manish.customagents.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRetirementEligibilityServiceTest {
    @Test
    void reportsAllNonTerminalRunCountsForTheAgent() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentRunRepository.StatusCount running = count(AgentRunStatus.RUNNING, 2);
        AgentRunRepository.StatusCount child = count(AgentRunStatus.WAITING_FOR_CHILD, 1);
        when(repository.countActiveByAgent(
                org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.eq("agent-1"), anySet()))
                .thenReturn(List.of(running, child));

        var result = new AgentRetirementEligibilityService(repository)
                .check("tenant-1", "agent-1");

        assertThat(result.eligible()).isFalse();
        assertThat(result.activeRunCount()).isEqualTo(3);
        assertThat(result.activeRunsByStatus()).containsEntry("WAITING_FOR_CHILD", 1L);
    }

    private AgentRunRepository.StatusCount count(AgentRunStatus status, long total) {
        return new AgentRunRepository.StatusCount() {
            @Override public AgentRunStatus getStatus() { return status; }
            @Override public long getTotal() { return total; }
        };
    }
}
