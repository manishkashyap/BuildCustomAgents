package com.manish.customagents.error;

import java.util.List;
import java.util.Map;

public class AgentRetirementBlockedException extends RuntimeException {
    private final long activeRunCount;
    private final Map<String, Long> activeRunsByStatus;
    private final List<Map<String, String>> dependentAgents;

    public AgentRetirementBlockedException(long activeRunCount,
            Map<String, Long> activeRunsByStatus, List<Map<String, String>> dependentAgents) {
        super("The agent has active runs or published-agent dependencies");
        this.activeRunCount = activeRunCount;
        this.activeRunsByStatus = Map.copyOf(activeRunsByStatus);
        this.dependentAgents = List.copyOf(dependentAgents);
    }

    public long activeRunCount() { return activeRunCount; }
    public Map<String, Long> activeRunsByStatus() { return activeRunsByStatus; }
    public List<Map<String, String>> dependentAgents() { return dependentAgents; }
}
