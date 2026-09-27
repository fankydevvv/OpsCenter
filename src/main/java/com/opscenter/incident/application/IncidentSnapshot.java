package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * Before/after picture of an incident written to {@code audit_logs} (01-SRS §15, TC-AUD-003). A
 * dedicated record rather than the entity or the API DTO: it holds exactly the fields an auditor
 * needs and nothing that could leak (the audit module rejects credential-like names anyway).
 */
public record IncidentSnapshot(UUID id, String incidentNo, IncidentStatus status, Severity severity,
                               IncidentSource source, UUID serviceId, UUID owningTeamId, UUID assigneeId,
                               String environment, String title, Instant acknowledgedAt, Instant investigatingAt,
                               Instant mitigatedAt, Instant resolvedAt, Instant reopenedAt, String rootCause,
                               String resolution, String mitigationSummary, long version) {

    public static IncidentSnapshot of(Incident incident) {
        return new IncidentSnapshot(incident.getId(), incident.getIncidentNo(), incident.getStatus(),
                incident.getSeverity(), incident.getSource(), incident.getServiceId(), incident.getOwningTeamId(),
                incident.getAssigneeId(), incident.getEnvironment(), incident.getTitle(), incident.getAcknowledgedAt(),
                incident.getInvestigatingAt(), incident.getMitigatedAt(), incident.getResolvedAt(),
                incident.getReopenedAt(), incident.getRootCause(), incident.getResolution(),
                incident.getMitigationSummary(), incident.getVersion());
    }
}
