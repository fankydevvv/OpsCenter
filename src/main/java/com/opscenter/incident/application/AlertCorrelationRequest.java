package com.opscenter.incident.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.shared.domain.Severity;

/**
 * What the alert module tells the incident module about a NEW logical alert (CREATED or REFIRED)
 * so it can be grouped (blueprint §8.5, FR-ALT-04, D-54).
 *
 * @param correlationKey        {@code sha256(alertName, service, ENV)} - the group of the alert (D-46)
 * @param serviceId             catalog service, {@code null} = UNMAPPED (D-49)
 * @param serviceCode           code from the labels, shown in the SERVICE_UNRESOLVED timeline note
 * @param environmentRegistered {@code false} = the service has no such environment row (timeline note, D-48)
 * @param severityDefaulted     the alert had no/unknown severity label (timeline note, D-45)
 * @param rawSeverity           the original label value, for that note
 * @param title                 incident title candidate (annotation summary -> description -> alert name)
 */
public record AlertCorrelationRequest(UUID organizationId, UUID alertId, String alertName, String instance,
                                      String correlationKey, Severity severity, boolean severityDefaulted,
                                      String rawSeverity, UUID serviceId, String serviceCode, UUID owningTeamId,
                                      String environment, boolean environmentRegistered, String title,
                                      String description, IncidentSource source, Instant occurredAt) {

    public AlertCorrelationRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(source, "source");
    }

    public boolean mapped() {
        return serviceId != null;
    }
}
