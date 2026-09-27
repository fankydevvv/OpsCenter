package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * Row of {@code GET /api/v1/incidents} (blueprint §7.4). {@code service = null} marks an UNMAPPED
 * incident (D-49); {@code version} must be echoed by every command (D-57).
 */
public record IncidentSummary(UUID id, String incidentNo, String title, Severity severity, Severity priority,
                              IncidentStatus status, IncidentSource source, ServiceBrief service, String environment,
                              TeamBrief owningTeam, UserBrief assignee, long occurrenceCount, long alertCount,
                              Instant createdAt, Instant acknowledgedAt, Instant resolvedAt, Instant updatedAt,
                              long version) {
}
