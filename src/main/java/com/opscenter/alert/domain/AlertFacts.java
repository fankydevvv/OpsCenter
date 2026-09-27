package com.opscenter.alert.domain;

import java.util.Objects;

import com.opscenter.shared.domain.Severity;

/**
 * The descriptive facts of an alert notification, already normalised (FR-ALT-02): what an
 * {@link Alert} row stores besides its lifecycle. Values are cut to their column lengths here, so a
 * source sending a 2 000-character label can never make the insert fail with "value too long".
 *
 * @param fingerprint {@code sha256(alertName, service, ENV, instance)} (D-46)
 */
public record AlertFacts(String alertName, String fingerprint, Severity severity, String serviceCode,
                         String environment, String instance, String summary, String externalAlertId) {

    public AlertFacts {
        alertName = cut(Objects.requireNonNull(alertName, "alertName"), 255);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(severity, "severity");
        serviceCode = cut(serviceCode, 100);
        environment = cut(environment, 50);
        instance = cut(instance, 255);
        externalAlertId = cut(externalAlertId, 255);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
