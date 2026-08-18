package com.manish.customagents.runtime.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeClockConfiguration {

    @Bean
    Clock runtimeClock() {
        return Clock.systemUTC();
    }
}
