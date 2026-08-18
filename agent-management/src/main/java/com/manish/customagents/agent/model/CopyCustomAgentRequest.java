package com.manish.customagents.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CopyCustomAgentRequest(
        @NotBlank @Size(min = 3, max = 120) String name) {}
