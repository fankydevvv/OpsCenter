package com.opscenter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Throw-away PostgreSQL 17 for integration tests (D-24).
 * <p>
 * {@code @ServiceConnection} lets Spring Boot derive the datasource <em>and</em> the Flyway
 * connection from the container, so no test ever carries a JDBC URL - and therefore can never
 * touch the developer database on localhost:5433 or the foreign {@code postgres_db} container.
 * The container is a Spring bean: started once per test context, stopped when the context closes.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {

    public static final String IMAGE = "postgres:17-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(IMAGE);
    }
}
