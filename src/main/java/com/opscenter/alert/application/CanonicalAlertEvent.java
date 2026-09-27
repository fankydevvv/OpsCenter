package com.opscenter.alert.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.shared.domain.Severity;

/**
 * One alert notification in OpsCenter's own vocabulary (FR-ALT-02, 04-API §23.1 "canonical
 * integration event", blueprint D-44).
 * <p>
 * Every connector translates its payload into this record (today: {@code AlertmanagerEventMapper}
 * in the integration module); the alert and incident modules only ever see this type. Adding a
 * generic webhook or a manual alert later therefore touches no dedup/grouping code.
 * <p>
 * All values are already normalised: {@code serviceCode} lower case, {@code environment} canonical
 * ({@code PRODUCTION}, not {@code prod}), {@code severity} mapped to P1..P4, sensitive label values
 * masked.
 *
 * @param index             position in the delivery (keeps the answer in request order)
 * @param eventId           unique id of this event (04-API §23.1)
 * @param status            FIRING = {@code ALERT_FIRING}, RESOLVED = {@code ALERT_RESOLVED}
 * @param occurredAt        when the source says it happened (startsAt / endsAt) - display only (R-33)
 * @param severityDefaulted the label was missing/unknown and {@link #severity} is the default (D-45)
 * @param rawSeverity       the original label value
 * @param externalAlertId   the source's own id (Alertmanager fingerprint over all labels) - not used for dedup
 * @param traceId           the request id of the delivery (04-API §23.1)
 */
public record CanonicalAlertEvent(int index, UUID eventId, String sourceCode, AlertSourceType sourceType,
                                  AlertStatus status, Instant occurredAt, String alertName, String serviceCode,
                                  String environment, String instance, Severity severity, boolean severityDefaulted,
                                  String rawSeverity, String summary, String description, String externalAlertId,
                                  String generatorUrl, Instant startsAt, Instant endsAt, Map<String, String> labels,
                                  Map<String, String> annotations, String traceId) {

    public CanonicalAlertEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(alertName, "alertName");
        Objects.requireNonNull(severity, "severity");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }

    /** {@code ALERT_FIRING} or {@code ALERT_RESOLVED} (04-API §23.1 eventType). */
    public String eventType() {
        return status == AlertStatus.FIRING ? "ALERT_FIRING" : "ALERT_RESOLVED";
    }

    /** Identity of the alert (dedup key, D-46). */
    public String fingerprint() {
        return Fingerprints.alert(alertName, serviceCode, environment, instance);
    }

    /** Identity of the alert's group (incident grouping and lock key, D-46, D-50). */
    public String correlationKey() {
        return Fingerprints.correlation(alertName, serviceCode, environment);
    }
}
