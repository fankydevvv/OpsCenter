package com.opscenter.alert.infrastructure;

import java.time.Instant;
import java.util.List;

import com.opscenter.alert.application.AlertQueryService;
import com.opscenter.alert.application.AlertSummary;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.incident.application.AlertBrief;
import com.opscenter.incident.application.AlertStatsReader;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert module's implementation of the incident module's {@link AlertStatsReader} port: the
 * alert counters and newest alerts of the Operations Center summary (blueprint §7.5).
 */
@Component
public class AlertStatsReaderAdapter implements AlertStatsReader {

    private final AlertRepository alerts;
    private final AlertQueryService queries;

    public AlertStatsReaderAdapter(AlertRepository alerts, AlertQueryService queries) {
        this.alerts = alerts;
        this.queries = queries;
    }

    @Override
    @Transactional(readOnly = true)
    public AlertCounters counters(Instant since) {
        return new AlertCounters(alerts.countByStatus(AlertStatus.FIRING),
                alerts.countByStatusAndServiceIdIsNull(AlertStatus.FIRING),
                alerts.countByFirstSeenAtGreaterThanEqual(since));
    }

    @Override
    public List<AlertBrief> recentAlerts(int limit) {
        return queries.recent(limit).stream().map(AlertStatsReaderAdapter::brief).toList();
    }

    private static AlertBrief brief(AlertSummary a) {
        return new AlertBrief(a.id(), a.alertName(), a.fingerprint(), a.severity(), a.status().name(),
                a.mappingStatus().name(), a.serviceId(), a.serviceCode(), a.serviceName(), a.environment(),
                a.instance(), a.summary(), a.occurrenceCount(), a.firstSeenAt(), a.lastSeenAt(), a.resolvedAt(),
                a.primaryIncident());
    }
}
