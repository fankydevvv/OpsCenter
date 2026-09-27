package com.opscenter.alert.application;

import java.util.Locale;

import com.opscenter.shared.domain.Severity;

import org.springframework.stereotype.Component;

/**
 * Maps a source's severity label to the one OpsCenter scale P1..P4 (01-SRS §21 "determine
 * severity", blueprint D-45). Prometheus rules write whatever their authors like
 * ({@code critical}, {@code Warning}, {@code page} ...); the table comes from
 * {@code opscenter.alert.severity-map}. A missing or unknown label falls back to
 * {@code default-severity} (P3) and is flagged, so the incident timeline can say
 * "severity defaulted" instead of silently guessing.
 */
@Component
public class SeverityMapping {

    private final AlertProperties properties;

    public SeverityMapping(AlertProperties properties) {
        this.properties = properties;
    }

    /**
     * @param severity  the mapped severity
     * @param defaulted {@code true} when the label was missing or not in the table
     * @param raw       the original label value (may be {@code null})
     */
    public record Decision(Severity severity, boolean defaulted, String raw) {
    }

    public Decision map(String label) {
        if (label == null || label.isBlank()) {
            return new Decision(properties.defaultSeverity(), true, null);
        }
        String key = label.trim().toLowerCase(Locale.ROOT);
        Severity mapped = properties.severityMap().get(key);
        if (mapped == null) {
            // "P1".."P4" written directly in the rule are understood as well
            mapped = Severity.parse(key).orElse(null);
        }
        return mapped == null
                ? new Decision(properties.defaultSeverity(), true, label.trim())
                : new Decision(mapped, false, label.trim());
    }
}
