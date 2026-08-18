package com.manish.customagents.runtime.spi;

import com.manish.customagents.runtime.model.BaseAgentRequest;
import com.manish.customagents.runtime.model.BaseAgentResponse;
import com.manish.customagents.runtime.model.ModelProvider;

/**
 * Provider boundary for one LLM generation request.
 *
 * <p>Implementations translate the provider-neutral request into a remote provider request and
 * normalize the remote response. Custom-agent orchestration and tool execution remain outside
 * this interface.</p>
 */
public interface LlmClient {

    ModelProvider provider();

    boolean supports(String model);

    BaseAgentResponse generate(BaseAgentRequest request);
}
