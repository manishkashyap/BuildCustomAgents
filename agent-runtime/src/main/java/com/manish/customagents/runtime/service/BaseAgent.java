package com.manish.customagents.runtime.service;

import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.registry.LlmClientRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Provider-neutral facade for a single generic-agent generation turn. */
@Component
public class BaseAgent {

    private final LlmClientRegistry clientRegistry;

    public BaseAgent(LlmClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    public BaseAgentResponse generate(BaseAgentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return clientRegistry.resolve(request.model()).generate(request);
    }
}
