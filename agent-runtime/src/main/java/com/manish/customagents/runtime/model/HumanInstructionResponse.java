package com.manish.customagents.runtime.model;

import java.time.Instant;

public record HumanInstructionResponse(
        String instructionId,
        String rootRunId,
        String targetRunId,
        String status,
        Instant createdAt) {
}
