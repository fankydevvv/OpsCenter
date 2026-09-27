package com.opscenter.incident.application;

import java.time.Instant;
import java.util.List;

/**
 * Port for the alert figures of the Operations Center summary (blueprint §7.5), implemented by the
 * alert module ({@code alert.infrastructure.AlertStatsReaderAdapter}) for the same
 * dependency-inversion reason as {@link LinkedAlertReader}.
 */
public interface AlertStatsReader {

    /** @param since start of the "received" window (now - 24 h) */
    AlertCounters counters(Instant since);

    /** Most recently seen alerts, newest first. */
    List<AlertBrief> recentAlerts(int limit);

    /**
     * @param firing          alerts currently FIRING
     * @param unmappedFiring  FIRING alerts without a catalog service (D-48)
     * @param receivedLast24h logical alerts first seen in the window
     */
    record AlertCounters(long firing, long unmappedFiring, long receivedLast24h) {
    }
}
