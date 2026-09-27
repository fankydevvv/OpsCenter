package com.opscenter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Throw-away MinIO for integration tests (blueprint §15, D-63).
 * <p>
 * Spring Boot has no service connection for "our own" {@code opscenter.storage.*} properties, so the
 * container's address is published with a {@link DynamicPropertyRegistrar} bean: its suppliers are
 * evaluated lazily, after the container bean has been started by Boot's Testcontainers lifecycle
 * support. Being beans (not a static {@code @DynamicPropertySource} on each test class) keeps all
 * integration tests on ONE cached Spring context and one set of containers (R-37).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MinioTestcontainersConfiguration {

    public static final String BUCKET = "opscenter-raw";

    @Bean
    MinioContainer minioContainer() {
        return new MinioContainer();
    }

    @Bean
    DynamicPropertyRegistrar minioProperties(MinioContainer minioContainer) {
        return registry -> {
            registry.add("opscenter.storage.enabled", () -> "true");
            registry.add("opscenter.storage.endpoint", minioContainer::s3Endpoint);
            registry.add("opscenter.storage.access-key", () -> MinioContainer.ACCESS_KEY);
            registry.add("opscenter.storage.secret-key", () -> MinioContainer.SECRET_KEY);
            registry.add("opscenter.storage.bucket", () -> BUCKET);
        };
    }
}
