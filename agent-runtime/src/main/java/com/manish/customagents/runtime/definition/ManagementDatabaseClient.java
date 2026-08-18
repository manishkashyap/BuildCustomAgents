package com.manish.customagents.runtime.definition;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.manish.customagents.runtime.config.ManagementDataSourceProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Owns the runtime's read-only connection pool to the management database. */
@Component
public class ManagementDatabaseClient {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public ManagementDatabaseClient(ManagementDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setPoolName("agent-management-read-pool");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        this.dataSource = new HikariDataSource(config);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
