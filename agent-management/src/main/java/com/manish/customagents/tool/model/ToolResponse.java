package com.manish.customagents.tool.model;

import com.manish.customagents.tool.enums.ToolStatus;

import java.time.Instant;

public record ToolResponse(
        String id,
        String licenseCode,
        ToolStatus status,
        int version,
        CreateToolRequest definition,
        Instant createdAt,
        Instant updatedAt) {
}
