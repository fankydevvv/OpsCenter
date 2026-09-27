package com.opscenter.support;

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class of every {@code *IT}: full Spring Boot context on a random port, {@code test}
 * profile (relay off, Rabbit health off, no datasource URL) and throw-away infrastructure from
 * Testcontainers: PostgreSQL migrated by Flyway (D-24), Redis (lock, resolution cache, rate
 * limit - D-50..D-53) and MinIO (object storage - D-41). Subclasses that need a broker add
 * {@code @Import(RabbitTestcontainersConfiguration.class)}.
 * <p>
 * {@code @AutoConfigureMockMvc} and {@code @AutoConfigureMetrics} sit here rather than on the few
 * classes that use them on purpose: Spring caches one context per distinct configuration, and
 * every distinct configuration means another set of containers plus another Flyway run. With
 * the annotations shared, all non-Rabbit integration tests reuse a single context and containers.
 * ({@code @AutoConfigureMetrics} is needed because Boot switches metrics export off in
 * {@code @SpringBootTest} contexts; without it {@code /actuator/prometheus} is not registered.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureMetrics
@Import({PostgresTestcontainersConfiguration.class, RedisTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class})
public abstract class AbstractIntegrationTest {
}
