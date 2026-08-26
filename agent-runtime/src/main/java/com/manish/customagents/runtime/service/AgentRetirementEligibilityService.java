package com.manish.customagents.runtime.service;

import com.manish.customagents.runtime.enums.AgentRunStatus;
import com.manish.customagents.contracts.RetirementEligibilityResponse;
import com.manish.customagents.runtime.repository.AgentRunRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRetirementEligibilityService {
    private static final Set<AgentRunStatus> ACTIVE = Set.of(
            AgentRunStatus.PENDING,
            AgentRunStatus.RUNNING,
            AgentRunStatus.WAITING_FOR_HUMAN,
            AgentRunStatus.WAITING_FOR_CHILD,
            AgentRunStatus.PAUSED);

    private final AgentRunRepository repository;

    public AgentRetirementEligibilityService(AgentRunRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public RetirementEligibilityResponse check(String licenseCode, String agentId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        repository.countActiveByAgent(licenseCode.strip(), agentId.strip(), ACTIVE)
                .forEach(count -> counts.put(count.getStatus().name(), count.getTotal()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new RetirementEligibilityResponse(total == 0, total, counts);
    }
}
