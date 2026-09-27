package com.opscenter.integration.application.alertmanager;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * One element of {@code alerts[]} of the Alertmanager webhook (blueprint §7.2).
 *
 * @param status       {@code firing} or {@code resolved}
 * @param labels       identity of the alert; {@code alertname} is mandatory, {@code service},
 *                     {@code environment}, {@code instance}, {@code severity} are used when present
 * @param annotations  human text ({@code summary}, {@code description}, {@code runbook_url} ...)
 * @param startsAt     when the alert started firing (source clock)
 * @param endsAt       when it resolved; Alertmanager sends {@code 0001-01-01T00:00:00Z} for "none"
 * @param generatorURL link back to the Prometheus expression
 * @param fingerprint  Alertmanager's own hash over ALL labels - kept as {@code external_alert_id}
 *                     only, OpsCenter computes its own fingerprint (D-46)
 */
public record AlertmanagerAlert(
        @NotNull @Pattern(regexp = "firing|resolved", message = "must be 'firing' or 'resolved'") String status,
        @NotNull @AlertNamePresent Map<String, String> labels,
        Map<String, String> annotations,
        @NotNull Instant startsAt,
        Instant endsAt,
        String generatorURL,
        String fingerprint) {
}
