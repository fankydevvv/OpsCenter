package com.opscenter.incident.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.opscenter.shared.domain.Severity;

/**
 * Port through which the incident module reads the alerts linked to its incidents (blueprint §4.1
 * "dependency inversion").
 * <p>
 * The alert module already depends on the incident module (it asks {@code IncidentCorrelationService}
 * to open or link incidents). If the incident module called the alert module back directly, the
 * two would depend on each other - a cycle. Instead the incident module <em>declares</em> what it
 * needs here, in its own package and with its own types, and the alert module <em>implements</em>
 * it ({@code alert.infrastructure.LinkedAlertReaderAdapter}). The dependency arrow keeps pointing
 * from alert to incident only.
 */
public interface LinkedAlertReader {

    /** Alerts by id in one query; unknown ids are absent from the map. */
    Map<UUID, LinkedAlertFacts> findAlerts(Collection<UUID> alertIds);

    /**
     * What the incident module may know about an alert.
     *
     * @param status {@code FIRING} or {@code RESOLVED}
     */
    record LinkedAlertFacts(UUID id, String alertName, Severity severity, String status, String instance,
                            long occurrenceCount, Instant lastSeenAt) {

        public boolean resolved() {
            return "RESOLVED".equals(status);
        }
    }
}
