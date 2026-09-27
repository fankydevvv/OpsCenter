package com.opscenter.shared.application.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-63: probes run in parallel on virtual threads, a hung probe is cut at the timeout, failures are
 * sanitised, components are ordered for display, and the overall status follows the
 * "postgres = DOWN, anything else = DEGRADED" rule.
 */
class SystemStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private SystemStatusService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.destroy();
        }
    }

    private SystemStatusService serviceWith(Duration timeout, ComponentProbe... probes) {
        service = new SystemStatusService(List.of(probes), timeout, Clock.fixed(NOW, ZoneOffset.UTC));
        return service;
    }

    private static ComponentProbe probe(String name, long sleepMs, ProbeResult result) {
        return new ComponentProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ProbeResult probe() throws Exception {
                Thread.sleep(sleepMs);
                return result;
            }
        };
    }

    private static ComponentProbe failing(String name, Exception failure) {
        return new ComponentProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ProbeResult probe() throws Exception {
                throw failure;
            }
        };
    }

    @Test
    void probesRunInParallel_andAreOrderedForDisplay() {
        ProbeResult up = ProbeResult.up("1.0", Map.of());
        SystemStatusService status = serviceWith(Duration.ofSeconds(5),
                probe("minio", 400, up), probe("redis", 400, up), probe("postgres", 400, up), probe("backend", 400, up),
                probe("zeta-extra", 400, up));

        long started = System.nanoTime();
        SystemStatusView view = status.check();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).as("5 x 400 ms in parallel, not 2 s in sequence").isLessThan(1500);
        assertThat(view.checkedAt()).isEqualTo(NOW);
        assertThat(view.components()).extracting(ComponentStatus::name)
                .containsExactly("backend", "postgres", "redis", "minio", "zeta-extra");
        assertThat(view.components()).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(ComponentState.UP);
            assertThat(c.latencyMs()).isGreaterThanOrEqualTo(350L);
        });
        assertThat(view.overallStatus()).isEqualTo(OverallStatus.UP);
    }

    @Test
    void aHungProbeIsReportedDownAtTheTimeout_withoutDelayingTheOthers() {
        SystemStatusService status = serviceWith(Duration.ofMillis(300),
                probe("postgres", 0, ProbeResult.up("17", Map.of())),
                probe("prometheus", 10_000, ProbeResult.up("3", Map.of())));

        long started = System.nanoTime();
        SystemStatusView view = status.check();

        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000);
        ComponentStatus prometheus = view.components().get(1);
        assertThat(prometheus.name()).isEqualTo("prometheus");
        assertThat(prometheus.status()).isEqualTo(ComponentState.DOWN);
        assertThat(prometheus.error()).isEqualTo("No answer within 300 ms");
        assertThat(view.overallStatus()).isEqualTo(OverallStatus.DEGRADED);
    }

    @Test
    void exceptionsBecomeDown_withTheRootCause_andCredentialsMasked() {
        SystemStatusService status = serviceWith(Duration.ofSeconds(2),
                failing("redis", new IllegalStateException("wrapper",
                        new java.net.ConnectException("refused redis://:s3cr3t@redis:6379"))));

        ComponentStatus redis = status.check().components().getFirst();

        assertThat(redis.status()).isEqualTo(ComponentState.DOWN);
        assertThat(redis.error()).isEqualTo("ConnectException: refused redis://***@redis:6379").doesNotContain("s3cr3t");
    }

    @Test
    void overall_postgresDownIsDown_otherDownIsDegraded_unknownIsNeutral() {
        ComponentStatus postgresDown = new ComponentStatus("postgres", ComponentState.DOWN, null, 1L, Map.of(), "x");
        ComponentStatus redisDown = new ComponentStatus("redis", ComponentState.DOWN, null, 1L, Map.of(), "x");
        ComponentStatus prometheusUnknown = new ComponentStatus("prometheus", ComponentState.UNKNOWN, null, null, Map.of(), "n/a");
        ComponentStatus backendUp = new ComponentStatus("backend", ComponentState.UP, "dev", 0L, Map.of(), null);

        assertThat(SystemStatusService.overall(List.of(backendUp, redisDown, postgresDown))).isEqualTo(OverallStatus.DOWN);
        assertThat(SystemStatusService.overall(List.of(backendUp, redisDown))).isEqualTo(OverallStatus.DEGRADED);
        assertThat(SystemStatusService.overall(List.of(backendUp, prometheusUnknown))).isEqualTo(OverallStatus.UP);
    }

    @Test
    void unknownResultsCarryNoLatency() {
        SystemStatusService status = serviceWith(Duration.ofSeconds(2),
                probe("alertmanager", 0, ProbeResult.unknown("not configured")));

        ComponentStatus alertmanager = status.check().components().getFirst();

        assertThat(alertmanager.status()).isEqualTo(ComponentState.UNKNOWN);
        assertThat(alertmanager.latencyMs()).isNull();
        assertThat(alertmanager.error()).isEqualTo("not configured");
    }

    @Test
    void probeErrors_truncateLongMessages() {
        String description = ProbeErrors.describe(new RuntimeException("x".repeat(500)));

        assertThat(description).hasSize(200).endsWith("...");
        assertThat(ProbeErrors.sanitize("amqp://guest:guest@127.0.0.1:1 refused")).isEqualTo("amqp://***@127.0.0.1:1 refused");
    }
}
