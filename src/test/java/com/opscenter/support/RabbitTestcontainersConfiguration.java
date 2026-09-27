package com.opscenter.support;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Throw-away RabbitMQ 4 for the tests that really talk to a broker ({@code OutboxRelayIT}, the
 * RabbitMQ status/health tests). Kept separate from the PostgreSQL configuration so the majority of
 * integration tests do not pay for a broker they never use (D-23).
 * <p>
 * The {@code test} profile switches Boot's {@code rabbit} health indicator off, because the main
 * test context has no broker and {@code /actuator/health} must be UP there. Contexts that DO have
 * a broker register the same Boot indicator here, so {@code /actuator/health} shows the
 * {@code rabbit} component exactly as in production (D-62).
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainersConfiguration {

    public static final String IMAGE = "rabbitmq:4-management-alpine";

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(IMAGE);
    }

    @Bean
    RabbitHealthIndicator rabbitHealthIndicator(RabbitTemplate rabbitTemplate) {
        return new RabbitHealthIndicator(rabbitTemplate);
    }
}
