package com.manish.customagents.egress.model;

import com.manish.customagents.egress.enums.EgressHostStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateEgressHostRequest(
        @NotNull EgressHostStatus status,
        @Size(max = 1000) String description) {
}
