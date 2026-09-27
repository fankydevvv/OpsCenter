package com.opscenter.alert.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.incident.application.IncidentRef;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.domain.Severity;

/**
 * Row of {@code GET /api/v1/alerts} (blueprint §7.3). {@code mappingStatus} is derived
 * ({@code service_id} present or not, D-48); {@code serviceCode} is what the source reported, so an
 * UNMAPPED row still tells the administrator which code to register.
 */
public record AlertSummary(UUID id, String alertName, String fingerprint, Severity severity, AlertStatus status,
                           MappingStatus mappingStatus, UUID serviceId, String serviceCode, String serviceName,
                           String environment, String instance, String summary, long occurrenceCount,
                           Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt, IncidentRef primaryIncident) {
}
