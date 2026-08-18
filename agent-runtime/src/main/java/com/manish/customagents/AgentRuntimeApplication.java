package com.manish.customagents;

import com.manish.customagents.runtime.config.AgentExecutionProperties;
import com.manish.customagents.runtime.config.DynamicHttpToolProperties;
import com.manish.customagents.runtime.config.DraftAgentTestProperties;
import com.manish.customagents.runtime.config.ManagementDataSourceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AgentExecutionProperties.class,
        DraftAgentTestProperties.class,
        ManagementDataSourceProperties.class,
        DynamicHttpToolProperties.class
})
public class AgentRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRuntimeApplication.class, args);
    }
}
