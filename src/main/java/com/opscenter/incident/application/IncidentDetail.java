package com.opscenter.incident.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentAction;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * {@code GET /api/v1/incidents/{id}} and the answer of every command (blueprint §7.4).
 * <p>
 * {@code allowedActions} = what the state machine allows in the current status <em>intersected</em>
 * with the caller's permissions. The UI renders its buttons from it, so the rule lives in one place
 * (the backend) instead of being duplicated in TypeScript.
 */
public record IncidentDetail(UUID id, String incidentNo, String title, Severity severity, Severity priority,
                             IncidentStatus status, IncidentSource source, ServiceBrief service, String environment,
                             TeamBrief owningTeam, UserBrief assignee, long occurrenceCount, long alertCount,
                             Instant createdAt, Instant acknowledgedAt, Instant resolvedAt, Instant updatedAt,
                             long version, String description, String fingerprint, Instant investigatingAt,
                             Instant mitigatedAt, Instant verifiedAt, Instant closedAt, Instant reopenedAt,
                             String rootCause, String resolution, String mitigationSummary, String recoverySummary,
                             List<LinkedAlertView> alerts, List<StatusHistoryView> statusHistory,
                             List<IncidentAction> allowedActions) {
}
