package com.opscenter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Throw-away Redis 7 for integration tests (blueprint §15): the distributed lock, the service
 * resolution cache and the rate limiter run against a real Redis, and {@code /actuator/health}
 * shows a real {@code redis} component.
 * <p>
 * A plain {@link GenericContainer} is enough: Spring Boot's Redis service-connection factory
 * accepts any container whose connection name is {@code redis}, so no extra Testcontainers module
 * is needed. The developer's Redis on localhost:6467 is never touched.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfiguration {

    public static final String IMAGE = "redis:7";
    public static final int PORT = 6379;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(PORT);
    }
}
