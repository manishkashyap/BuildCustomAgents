package com.manish.customagents.tool.model;

import com.manish.customagents.tool.enums.ToolStatus;

import java.time.Instant;

public record ToolStatusResponse(
        String id,
        String licenseCode,
        ToolStatus status,
        int version,
        Instant updatedAt) {
}
