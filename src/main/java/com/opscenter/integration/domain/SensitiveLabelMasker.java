package com.opscenter.integration.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks label/annotation values whose NAME looks like a credential before they are stored in
 * PostgreSQL (03-DB §30 "limit sensitive data", blueprint D-42, R-28).
 * <p>
 * Prometheus rules sometimes copy connection strings or tokens into labels
 * ({@code db_password="..."}, {@code api_key="..."}). The alert tables, the API and the UI only need
 * to know that such a label exists, never its value, so the value becomes {@code ***}. The
 * verbatim body is still available to administrators from object storage
 * ({@code alert.raw.read}).
 */
public final class SensitiveLabelMasker {

    public static final String MASK = "***";

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[_-]?key|authorization|credential|private[_-]?key|cookie).*");

    private SensitiveLabelMasker() {
    }

    public static boolean isSensitive(String name) {
        return name != null && SENSITIVE_NAME.matcher(name).matches();
    }

    /**
     * A copy of {@code values} (order kept) with sensitive values replaced by {@link #MASK}. Entries
     * with a {@code null} name or value ({@code "team": null} in a generic webhook's JSON) are dropped:
     * a label without a value carries no information, and the immutable maps downstream reject
     * {@code null} - one such label must not turn the whole delivery into a 500.
     */
    public static Map<String, String> mask(Map<String, String> values) {
        Map<String, String> masked = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((name, value) -> {
                if (name != null && value != null) {
                    masked.put(name, isSensitive(name) ? MASK : value);
                }
            });
        }
        return masked;
    }
}
