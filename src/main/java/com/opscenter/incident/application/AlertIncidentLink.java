package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opscenter.incident.domain.AlertRelationType;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * One incident an alert belongs to, as shown on the alert detail page (blueprint §7.3
 * {@code incidents[]}). {@code isPrimary} is named explicitly for JSON because bean naming rules
 * would otherwise be free to render a boolean {@code isX} as {@code x}.
 */
public record AlertIncidentLink(UUID id, String incidentNo, IncidentStatus status, Severity severity,
                                AlertRelationType relationType, @JsonProperty("isPrimary") boolean isPrimary,
                                Instant linkedAt) {
}
