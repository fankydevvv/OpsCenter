package com.opscenter.incident.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.incident.infrastructure.IncidentRepository;
import com.opscenter.shared.domain.Severity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the Operations Center summary (blueprint §7.5): a handful of aggregate queries instead of
 * shipping every incident to the browser. The page polls it every 10 s (D-58), so each figure is one
 * indexed {@code count}/{@code group by}.
 */
@Service
public class OperationsSummaryService {

    private static final int RECENT = 10;

    private final IncidentRepository incidents;
    private final IncidentViews views;
    private final AlertStatsReader alertStats;
    private final Clock clock;

    public OperationsSummaryService(IncidentRepository incidents, IncidentViews views, AlertStatsReader alertStats,
                                    Clock clock) {
        this.incidents = incidents;
        this.views = views;
        this.alertStats = alertStats;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperationsSummary summary(Collection<String> callerPermissions) {
        Instant now = clock.instant();
        Set<IncidentStatus> open = IncidentStatus.openStates();

        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity, 0L);
        }
        for (Object[] row : incidents.countBySeverity(open)) {
            bySeverity.put((Severity) row[0], ((Number) row[1]).longValue());
        }
        Map<IncidentStatus, Long> byStatus = new EnumMap<>(IncidentStatus.class);
        for (IncidentStatus status : open) {
            byStatus.put(status, 0L);
        }
        for (Object[] row : incidents.countByStatus(open)) {
            byStatus.put((IncidentStatus) row[0], ((Number) row[1]).longValue());
        }
        OperationsSummary.OpenIncidents openIncidents = new OperationsSummary.OpenIncidents(
                incidents.countByStatusIn(open), bySeverity, byStatus, incidents.countByStatusInAndServiceIdIsNull(open));

        List<IncidentSummary> recentIncidents = views.summaries(incidents.findTop10ByStatusInOrderByCreatedAtDesc(open));

        boolean canReadAlerts = callerPermissions.contains(IncidentPermissions.ALERT_READ);
        return new OperationsSummary(now, openIncidents,
                canReadAlerts ? alertStats.counters(now.minus(Duration.ofHours(24))) : null,
                recentIncidents,
                canReadAlerts ? alertStats.recentAlerts(RECENT) : null);
    }
}
