package com.opscenter.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent deliveries through the real HTTP stack (Tomcat threads, Redis lock, PostgreSQL) -
 * blueprint D-50/D-51, TC-DEDUP-001/002 under concurrency, PERF-01 "duplicate handling".
 * <p>
 * All requests are released at the same instant by a latch. Without the correlation-group lock,
 * several of them would read "no firing alert / no open incident" and create one each; the tests
 * prove that exactly one incident and one firing alert per fingerprint exist afterwards.
 */
class ConcurrentIngestionIT extends WebhookIntegrationTest {

    private static final int THREADS = 10;

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /** Fires all bodies at once; returns the HTTP status of each. */
    private List<Integer> fireConcurrently(List<byte[]> bodies, List<String> webhookIds) throws Exception {
        RestClient client = client();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < bodies.size(); i++) {
                byte[] body = bodies.get(i);
                String webhookId = webhookIds == null ? null : webhookIds.get(i);
                Callable<Integer> call = () -> {
                    start.await();
                    RestClient.RequestBodySpec request = client.post().uri(WEBHOOK)
                            .contentType(MediaType.APPLICATION_JSON);
                    if (webhookId != null) {
                        request.header("X-Webhook-Id", webhookId);
                    }
                    ResponseEntity<String> response = request.body(body).retrieve().toEntity(String.class);
                    return response.getStatusCode().value();
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateDeliveries_ofOneAlert_createExactlyOneAlertAndOneIncident() throws Exception {
        String name = unique("Storm-");
        byte[] body = json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "s:1", "critical"),
                null, T0)));
        List<byte[]> bodies = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(body);
            ids.add(unique("storm-" + i + "-")); // distinct deliveries: idempotency does not help here, the lock must
        }

        List<Integer> statuses = fireConcurrently(bodies, ids);

        assertThat(statuses).containsOnly(200);
        assertThat(count("select count(*) from alerts where alert_name = ? and status = 'FIRING'", name)).isEqualTo(1);
        assertThat(count("select occurrence_count from alerts where alert_name = ?", name)).isEqualTo(THREADS);
        assertThat(count("select count(distinct ia.incident_id) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select i.occurrence_count from incidents i join incident_alerts ia on ia.incident_id = i.id "
                + "join alerts a on a.id = ia.alert_id where a.alert_name = ?", name)).isEqualTo(THREADS);
        List<String> outcomes = jdbc.queryForList("select o.payload->>'outcome' from alert_occurrences o "
                + "join alerts a on a.id = o.alert_id where a.alert_name = ?", String.class, name);
        assertThat(outcomes).hasSize(THREADS);
        assertThat(outcomes.stream().filter("CREATED"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("DEDUPLICATED"::equals).count()).isEqualTo(THREADS - 1);
    }

    @Test
    void concurrentIdenticalBodies_areOneDelivery_andCreateExactlyOneIncident() throws Exception {
        String name = unique("SameBody-");
        byte[] body = json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "s:1", "warning"),
                null, T0)));
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(body);
        }

        List<Integer> statuses = fireConcurrently(bodies, null);

        // one processes, the others see the claimed key: 200 replay, or 409 IDEMPOTENCY_IN_PROGRESS while it runs
        assertThat(statuses).allMatch(s -> s == 200 || s == 409).contains(200);
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select occurrence_count from alerts where alert_name = ?", name)).isEqualTo(1);
        assertThat(count("select count(distinct ia.incident_id) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
    }

    @Test
    void concurrentSiblings_ofOneGroup_areGroupedIntoExactlyOneIncident() throws Exception {
        String name = unique("Siblings-");
        List<byte[]> bodies = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            bodies.add(json.writeValueAsBytes(payload(firing(labels(name, "odoo-erp", "DEV", "node-" + i + ":9100",
                    "warning"), null, T0))));
        }

        List<Integer> statuses = fireConcurrently(bodies, null);

        assertThat(statuses).containsOnly(200);
        assertThat(count("select count(*) from alerts where alert_name = ?", name)).isEqualTo(THREADS);
        assertThat(count("select count(distinct ia.incident_id) from incident_alerts ia join alerts a on a.id = ia.alert_id "
                + "where a.alert_name = ?", name)).isEqualTo(1);
        Map<String, Object> links = jdbc.queryForMap("select count(*) filter (where ia.relation_type = 'TRIGGER') as triggers, "
                + "count(*) filter (where ia.is_primary) as primaries from incident_alerts ia join alerts a "
                + "on a.id = ia.alert_id where a.alert_name = ?", name);
        assertThat(links).containsEntry("triggers", 1L).containsEntry("primaries", 1L);
    }
}
