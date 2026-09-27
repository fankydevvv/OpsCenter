package com.opscenter.shared.infrastructure.config;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The profile files are configuration code too (05-DEPLOY §9): this test resolves
 * {@code application.yml} + {@code application-<profile>.yml} exactly like Spring would (profile
 * file first) against a fake OS environment, without starting anything.
 * <ul>
 *   <li>{@code local}: DEV-only defaults point at the root docker-compose.yml ports, and the values
 *       of the root {@code .env} (POSTGRES_*, REDIS_PASSWORD, MINIO_ROOT_* ...) win over them.</li>
 *   <li>{@code dev} (container): no default for object storage - a missing endpoint fails fast; the
 *       task's {@code MINIO_*} names are accepted as aliases of {@code OBJECT_STORAGE_*}.</li>
 * </ul>
 */
class ApplicationProfilesTest {

    private static StandardEnvironment environment(String profile, Map<String, Object> osEnvironment) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        // the developer's real environment must not influence the assertions
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("fake-os-environment", osEnvironment));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application-" + profile, new ClassPathResource("application-" + profile + ".yml"))
                .forEach(sources::addLast);
        loader.load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
        return environment;
    }

    @Test
    void local_defaultsPointAtTheComposeInfrastructure() throws IOException {
        StandardEnvironment local = environment("local", Map.of());

        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:5433/opscenter");
        assertThat(local.getProperty("spring.data.redis.url")).isEqualTo("redis://:opscenter@localhost:6467");
        assertThat(local.getProperty("spring.rabbitmq.addresses")).isEqualTo("amqp://opscenter:opscenter@localhost:5672");
        assertThat(local.getProperty("opscenter.storage.endpoint")).isEqualTo("http://localhost:9002");
        assertThat(local.getProperty("opscenter.storage.access-key")).isEqualTo("opscenter");
        assertThat(local.getProperty("opscenter.storage.bucket")).isEqualTo("opscenter-raw");
        assertThat(local.getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration,classpath:db/seed-dev,classpath:db/seed-demo");
        assertThat(local.getProperty("opscenter.system.probes.prometheus-url")).isEqualTo("http://localhost:9090");
        assertThat(local.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState,db");
        assertThat(local.getProperty("opscenter.service-catalog.resolution.service-label-keys[0]")).isEqualTo("service");
    }

    @Test
    void local_followsTheValuesOfTheRootEnvFile() throws IOException {
        StandardEnvironment local = environment("local", Map.of(
                "POSTGRES_PORT", "6543", "POSTGRES_DB", "ops", "POSTGRES_PASSWORD", "pg-pw",
                "REDIS_PASSWORD", "redis-pw", "RABBITMQ_DEFAULT_PASS", "mq-pw",
                "MINIO_ROOT_USER", "minio-user", "MINIO_API_PORT", "9102",
                "OPSCENTER_ALERTMANAGER_TOKEN", "from-dot-env-0123456789abcdef0123456789"));

        assertThat(local.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://localhost:6543/ops");
        assertThat(local.getProperty("spring.datasource.password")).isEqualTo("pg-pw");
        assertThat(local.getProperty("spring.data.redis.url")).isEqualTo("redis://:redis-pw@localhost:6467");
        assertThat(local.getProperty("spring.rabbitmq.addresses")).isEqualTo("amqp://opscenter:mq-pw@localhost:5672");
        assertThat(local.getProperty("opscenter.storage.endpoint")).isEqualTo("http://localhost:9102");
        assertThat(local.getProperty("opscenter.storage.access-key")).isEqualTo("minio-user");
        assertThat(local.getProperty("opscenter.integration.alertmanager.token"))
                .isEqualTo("from-dot-env-0123456789abcdef0123456789");
    }

    @Test
    void dev_requiresTheStorageEndpoint_andAcceptsTheMinioAliases() throws IOException {
        StandardEnvironment missing = environment("dev", Map.of());
        assertThatThrownBy(() -> missing.getProperty("opscenter.storage.endpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MINIO_ENDPOINT");
        assertThat(missing.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");

        StandardEnvironment compose = environment("dev", Map.of(
                "OBJECT_STORAGE_ENDPOINT", "http://minio:9000", "OBJECT_STORAGE_ACCESS_KEY", "ak",
                "OBJECT_STORAGE_SECRET_KEY", "sk", "OBJECT_STORAGE_BUCKET", "raw-bucket",
                "OPSCENTER_FLYWAY_LOCATIONS", "classpath:db/migration,classpath:db/seed-demo"));
        assertThat(compose.getProperty("opscenter.storage.endpoint")).isEqualTo("http://minio:9000");
        assertThat(compose.getProperty("opscenter.storage.bucket")).isEqualTo("raw-bucket");
        assertThat(compose.getProperty("spring.flyway.locations")).doesNotContain("seed-dev").contains("seed-demo");

        StandardEnvironment aliases = environment("dev", Map.of(
                "MINIO_ENDPOINT", "http://storage:9000", "MINIO_ACCESS_KEY", "ak2", "MINIO_SECRET_KEY", "sk2",
                "MINIO_BUCKET", "alias-bucket"));
        assertThat(aliases.getProperty("opscenter.storage.endpoint")).isEqualTo("http://storage:9000");
        assertThat(aliases.getProperty("opscenter.storage.secret-key")).isEqualTo("sk2");
        assertThat(aliases.getProperty("opscenter.storage.bucket")).isEqualTo("alias-bucket");
    }
}
