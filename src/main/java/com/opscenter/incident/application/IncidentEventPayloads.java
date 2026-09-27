package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * JSON payloads of the incident outbox events (blueprint §9.1). Records instead of ad-hoc maps so
 * the contract with future consumers (notifications, WebSocket gateway - Sprint 3) is visible in
 * one file. No payload contains free text written by a person (root cause, notes): events travel
 * through RabbitMQ and may be logged by consumers; the text stays in the database.
 */
public final class IncidentEventPayloads {

    private IncidentEventPayloads() {
    }

    /** {@code IncidentCreated} */
    public record Created(UUID incidentId, String incidentNo, String title, Severity severity, IncidentStatus status,
                          IncidentSource source, UUID serviceId, String environment, UUID owningTeamId,
                          UUID primaryAlertId, Instant createdAt) {
    }

    /**
     * {@code IncidentUpdated} - a change that is not a status transition.
     *
     * @param change {@code ALERT_LINKED}, {@code SEVERITY_RAISED} or {@code ALL_ALERTS_RESOLVED}
     */
    public record Updated(UUID incidentId, String incidentNo, String change, Severity severity, long version) {
    }

    /**
     * {@code IncidentAcknowledged}, {@code IncidentInvestigationStarted}, {@code IncidentMitigated},
     * {@code IncidentResolved}, {@code IncidentClosed}, {@code IncidentReopened}.
     *
     * @param actorId          {@code null} for a system transition (REOPENED)
     * @param rootCausePresent only meaningful for {@code IncidentResolved}
     */
    public record StatusChanged(UUID incidentId, String incidentNo, IncidentStatus fromStatus, IncidentStatus toStatus,
                                UUID actorId, Instant occurredAt, long version, String reason,
                                Boolean rootCausePresent) {
    }
}
