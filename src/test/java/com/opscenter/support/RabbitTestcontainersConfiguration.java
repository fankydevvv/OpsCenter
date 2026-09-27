package com.opscenter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Throw-away RabbitMQ 4 for the tests that really publish messages ({@code OutboxRelayIT}).
 * Kept separate from the PostgreSQL configuration so the majority of integration tests do not pay
 * for a broker they never use (D-23).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainersConfiguration {

    public static final String IMAGE = "rabbitmq:4-management-alpine";

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(IMAGE);
    }
}
