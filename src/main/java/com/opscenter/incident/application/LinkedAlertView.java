package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opscenter.incident.domain.AlertRelationType;
import com.opscenter.shared.domain.Severity;

/**
 * An alert of the incident, as listed on the "Alerts" tab of {@code /incidents/[id]} (blueprint §7.4).
 * The alert columns come from the alert module through {@link LinkedAlertReader}.
 *
 * @param status {@code FIRING} or {@code RESOLVED}
 */
public record LinkedAlertView(UUID alertId, String alertName, Severity severity, String status, String instance,
                              AlertRelationType relationType, @JsonProperty("isPrimary") boolean isPrimary,
                              Instant linkedAt, long occurrenceCount, Instant lastSeenAt) {
}
