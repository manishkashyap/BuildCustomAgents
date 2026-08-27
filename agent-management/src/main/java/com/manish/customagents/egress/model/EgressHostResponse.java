package com.manish.customagents.egress.model;

import com.manish.customagents.egress.enums.EgressHostStatus;
import java.time.Instant;

public record EgressHostResponse(
        String id,
        String licenseCode,
        String hostPattern,
        String description,
        EgressHostStatus status,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy) {
}
