package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.shared.domain.Severity;

/**
 * An alert row of the Operations Center ({@code recentAlerts}, blueprint §7.5). It has the same JSON
 * shape as the alert module's {@code AlertSummary} so the UI reuses one type, but it is declared
 * here: the incident module may not import alert classes (see {@link AlertStatsReader}).
 *
 * @param status        {@code FIRING} or {@code RESOLVED}
 * @param mappingStatus {@code MAPPED} or {@code UNMAPPED}
 */
public record AlertBrief(UUID id, String alertName, String fingerprint, Severity severity, String status,
                         String mappingStatus, UUID serviceId, String serviceCode, String serviceName,
                         String environment, String instance, String summary, long occurrenceCount,
                         Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt, IncidentRef primaryIncident) {
}
