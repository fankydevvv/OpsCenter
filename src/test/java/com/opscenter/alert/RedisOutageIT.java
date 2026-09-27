package com.opscenter.alert;

import java.time.Duration;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;
import com.opscenter.shared.infrastructure.redis.RedisAvailability;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding "Redis outage costs ~8 s per webhook": with Redis <em>really</em> stopped (not a
 * mock), a delivery must still be answered 200 well inside Alertmanager's 10 s webhook timeout.
 * Lettuce rejects commands while disconnected ({@code RedisClientConfig}) and the first failure opens
 * the shared breaker ({@link RedisAvailability}), so the lock, the rate limiter and the resolution
 * cache go straight to their fallbacks.
 * <p>
 * The unique property below gives this class its own Spring context - and therefore its own Redis
 * container, which it may stop without disturbing the Redis every other integration test shares.
 */
@TestPropertySource(properties = "opscenter.test.isolated-context=redis-outage")
class RedisOutageIT extends WebhookIntegrationTest {

    @Autowired
    @Qualifier("redisContainer")
    GenericContainer<?> redisContainer;

    @Autowired
    RedisAvailability redisAvailability;

    @Autowired
    MeterRegistry meters;

    @Test
    void redisStopped_webhookStillAnswers200_inUnder3Seconds_andIngestionIsComplete() throws Exception {
        // warm-up with Redis running (class loading, first connections) - not part of the measurement
        deliver(payload(firing(labels(unique("Warmup-"), "odoo-erp", "DEV", "w:1", "info"), null, T0)));
        assertThat(redisAvailability.isAvailable()).isTrue();

        redisContainer.stop();
        double fallbacksBefore = meters.counter("opscenter.lock.fallback").count();

        String name = unique("RedisGone-");
        long started = System.nanoTime();
        JsonNode summary = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "g:1", "critical"), null, T0),
                firing(labels(unique("RedisGone2-"), "odoo-erp", "DEV", "g:2", "warning"), null, T0)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).as("one delivery with Redis down").isLessThan(Duration.ofSeconds(3));
        assertThat(summary.get("alertsCreated").asLong()).isEqualTo(2);
        assertThat(summary.get("incidentsCreated").asLong()).isEqualTo(2);
        assertThat(redisAvailability.isAvailable()).as("breaker open after the first failure").isFalse();
        assertThat(meters.counter("opscenter.lock.fallback").count() - fallbacksBefore)
                .as("both group locks came from PostgreSQL").isEqualTo(2.0);

        // the next delivery does not even try Redis: still fast, still complete
        long again = System.nanoTime();
        JsonNode repeat = deliver(payload(firing(labels(name, "odoo-erp", "DEV", "g:1", "critical"), null,
                T0.plusSeconds(60))));
        assertThat(Duration.ofNanos(System.nanoTime() - again)).isLessThan(Duration.ofSeconds(3));
        assertThat(repeat.at("/items/0/outcome").asString()).isEqualTo("DEDUPLICATED");
    }
}
