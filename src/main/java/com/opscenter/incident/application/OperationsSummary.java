package com.opscenter.incident.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * {@code GET /api/v1/operations/summary} - the numbers of the Operations Center page (02-SAD §18,
 * blueprint §7.5). {@code alerts} and {@code recentAlerts} are {@code null} when the caller lacks
 * {@code alert.read}: a coordinator with incident rights only still gets a working page.
 */
public record OperationsSummary(Instant generatedAt, OpenIncidents openIncidents,
                                AlertStatsReader.AlertCounters alerts, List<IncidentSummary> recentIncidents,
                                List<AlertBrief> recentAlerts) {

    /**
     * @param bySeverity always contains P1..P4 (0 when none), so the four KPI tiles need no null checks
     * @param byStatus   every open status (0 when none)
     * @param unmapped   open incidents without a service - the fallback queue of D-49
     */
    public record OpenIncidents(long total, Map<Severity, Long> bySeverity, Map<IncidentStatus, Long> byStatus,
                                long unmapped) {
    }
}
