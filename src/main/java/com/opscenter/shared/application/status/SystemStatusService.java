package com.opscenter.shared.application.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the component status page of the administrator ({@code GET /api/v1/system/status}, D-63).
 * <p>
 * Why parallel with a hard timeout: the page is most useful exactly when something is broken, and a
 * broken component usually does not fail fast - it hangs until a TCP timeout. Running every
 * {@link ComponentProbe} at the same time on a <b>virtual thread</b> (Java 21: a blocked virtual
 * thread costs almost nothing) and waiting at most {@code opscenter.system.probes.timeout} (2 s)
 * for all of them bounds the whole request to ~2 s whatever is down. A probe that misses the
 * deadline is reported {@code DOWN} with "no answer within 2000 ms"; its thread is interrupted and
 * left to finish on its own.
 */
@Service
public class SystemStatusService implements DisposableBean {

    /** Display order of the known components; anything else follows alphabetically. */
    static final List<String> DISPLAY_ORDER = List.of(
            "backend", "postgres", "redis", "rabbitmq", "minio", "prometheus", "alertmanager");

    /** Component whose failure makes the whole platform DOWN (it is the source of truth). */
    static final String CRITICAL_COMPONENT = "postgres";

    private final List<ComponentProbe> probes;
    private final Duration timeout;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SystemStatusService(List<ComponentProbe> probes,
                               @Value("${opscenter.system.probes.timeout:PT2S}") Duration timeout, Clock clock) {
        this.probes = List.copyOf(probes);
        this.timeout = timeout;
        this.clock = clock;
    }

    public SystemStatusView check() {
        Instant checkedAt = clock.instant();
        Map<ComponentProbe, Future<ComponentStatus>> running = new LinkedHashMap<>();
        for (ComponentProbe probe : probes) {
            running.put(probe, executor.submit(() -> run(probe)));
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        List<ComponentStatus> components = new ArrayList<>();
        for (Map.Entry<ComponentProbe, Future<ComponentStatus>> entry : running.entrySet()) {
            components.add(await(entry.getKey(), entry.getValue(), deadline));
        }
        components.sort(Comparator.comparingInt((ComponentStatus c) -> orderOf(c.name()))
                .thenComparing(ComponentStatus::name));
        return new SystemStatusView(checkedAt, overall(components), components);
    }

    private ComponentStatus await(ComponentProbe probe, Future<ComponentStatus> future, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            return new ComponentStatus(probe.name(), ComponentState.DOWN, null, timeout.toMillis(), Map.of(),
                    "No answer within " + timeout.toMillis() + " ms");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new ComponentStatus(probe.name(), ComponentState.UNKNOWN, null, null, Map.of(), "Check interrupted");
        }
        catch (ExecutionException ex) {
            // run() never throws; kept for completeness
            return new ComponentStatus(probe.name(), ComponentState.DOWN, null, null, Map.of(),
                    ProbeErrors.describe(ex));
        }
    }

    /** Executes one probe and measures it; any exception becomes DOWN with a sanitised reason. */
    private static ComponentStatus run(ComponentProbe probe) {
        long started = System.nanoTime();
        ProbeResult result;
        try {
            result = probe.probe();
            if (result == null) {
                result = ProbeResult.down("Probe returned no result", Map.of());
            }
        }
        catch (Exception ex) {
            result = ProbeResult.down(ProbeErrors.describe(ex), Map.of());
        }
        Long latencyMs = result.state() == ComponentState.UNKNOWN ? null
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new ComponentStatus(probe.name(), result.state(), result.version(), latencyMs, result.details(),
                result.error());
    }

    /** D-63: DOWN if PostgreSQL is down, DEGRADED if anything else is down, UP otherwise (UNKNOWN is neutral). */
    static OverallStatus overall(List<ComponentStatus> components) {
        boolean anyDown = false;
        for (ComponentStatus component : components) {
            if (component.status() == ComponentState.DOWN) {
                if (CRITICAL_COMPONENT.equals(component.name())) {
                    return OverallStatus.DOWN;
                }
                anyDown = true;
            }
        }
        return anyDown ? OverallStatus.DEGRADED : OverallStatus.UP;
    }

    private static int orderOf(String name) {
        int index = DISPLAY_ORDER.indexOf(name);
        return index < 0 ? DISPLAY_ORDER.size() : index;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
