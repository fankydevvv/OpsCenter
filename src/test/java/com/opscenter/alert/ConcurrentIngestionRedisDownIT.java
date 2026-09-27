package com.opscenter.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;
import com.opscenter.shared.infrastructure.redis.RedisDistributedLock;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Blueprint D-51 "defence in depth": when Redis cannot grant the lock (here: every lock attempt
 * fails with a connection error), the ingestion falls back to a PostgreSQL transaction-level
 * advisory lock and concurrent deliveries of one alert group are still serialised - exactly one
 * alert and one incident.
 * <p>
 * Runs in its own Spring context (the Redis half of the lock is replaced by a mock), so it does not
 * disturb the Redis used by the other integration tests.
 */
class ConcurrentIngestionRedisDownIT extends WebhookIntegrationTest {

    private static final int THREADS = 8;

    @MockitoBean
    RedisDistributedLock redisLock;

    @Autowired
    MeterRegistry meters;

    @LocalServerPort
    int port;

    @Test
    void redisUnavailable_lockFallsBackToPostgres_andStillOneIncident() throws Exception {
        when(redisLock.acquire(anyString(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("simulated: Redis is down"));
        double fallbacksBefore = meters.counter("opscenter.lock.fallback").count();
        String name = unique("RedisDown-");
        byte[] body = json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "x:1", "critical"),
                null, T0)));
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                String deliveryId = unique("rd-" + i + "-");
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.post().uri(WEBHOOK).contentType(MediaType.APPLICATION_JSON)
                            .header("X-Webhook-Id", deliveryId).body(body).retrieve().toBodilessEntity()
                            .getStatusCode().value();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select occurrence_count from alerts where alert_name = ?", name)).isEqualTo(THREADS);
        assertThat(count("select count(distinct ia.incident_id) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
        assertThat(meters.counter("opscenter.lock.fallback").count() - fallbacksBefore).isEqualTo(THREADS);
    }
}
