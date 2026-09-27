package com.opscenter.alert.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.opscenter.shared.domain.Severity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * {@code opscenter.alert.*} (blueprint §11, D-45, D-50, D-54).
 *
 * @param severityMap     label value (case-insensitive) -> severity; see {@link #DEFAULT_SEVERITY_MAP}
 * @param defaultSeverity used when the {@code severity} label is missing or unknown (a timeline note says so)
 * @param reopenWindow    a RESOLVED incident whose group fires again within this window is REOPENED;
 *                        {@code PT0S} disables automatic reopening (R-26)
 * @param lock            the correlation-group lock (Redis {@code SET NX PX}, PostgreSQL fallback)
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.alert")
public record AlertProperties(
        Map<String, Severity> severityMap,
        @NotNull @DefaultValue("P3") Severity defaultSeverity,
        @NotNull @DefaultValue("PT24H") Duration reopenWindow,
        @Valid @DefaultValue Lock lock) {

    /** D-45: critical -> P1; high/error/major -> P2; warning/medium/minor -> P3; info/low/none -> P4. */
    public static final Map<String, Severity> DEFAULT_SEVERITY_MAP = Map.of(
            "critical", Severity.P1,
            "high", Severity.P2, "error", Severity.P2, "major", Severity.P2,
            "warning", Severity.P3, "medium", Severity.P3, "minor", Severity.P3,
            "info", Severity.P4, "low", Severity.P4, "none", Severity.P4);

    public AlertProperties {
        Map<String, Severity> source = severityMap == null || severityMap.isEmpty() ? DEFAULT_SEVERITY_MAP : severityMap;
        Map<String, Severity> normalised = new LinkedHashMap<>();
        source.forEach((label, severity) -> normalised.put(label.trim().toLowerCase(Locale.ROOT), severity));
        severityMap = Map.copyOf(normalised);
    }

    /**
     * {@code wait} cannot be a record component name (it would clash with {@code Object.wait()}), so
     * the Java name is {@code waitTime} and {@code @Name} binds it to the property {@code lock.wait}.
     *
     * @param ttl      safety expiry of the Redis key if this process dies while holding it
     * @param waitTime how long a delivery waits for a busy group before answering 503 ALERT_INGESTION_BUSY
     */
    public record Lock(@NotNull @DefaultValue("PT15S") Duration ttl,
                       @Name("wait") @NotNull @DefaultValue("PT5S") Duration waitTime) {
    }
}
