package com.manish.customagents.tool.model;

import com.manish.customagents.tool.enums.ToolStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateToolStatusRequest(@NotNull ToolStatus status) {
}
