package com.manish.customagents.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.ExecutableToolTypes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Management rejects a publish naming a tool type the runtime cannot execute, and it decides that
 * from ExecutableToolTypes. If an executor is added or removed without updating that set the two
 * services disagree silently, so the build asserts they match.
 */
class ExecutableToolTypesContractTest {

    @Test
    void contractMatchesTheRegisteredExecutors() {
        ToolExecutorRegistry registry = new ToolExecutorRegistry(List.of(
                new HttpToolExecutor(
                        RestClient.builder().build(), new ObjectMapper(), null),
                new BuiltInToolExecutor(),
                new CustomAgentToolExecutor()));

        assertThat(registry.supportedTypes())
                .containsExactlyInAnyOrderElementsOf(ExecutableToolTypes.supported());
    }
}
