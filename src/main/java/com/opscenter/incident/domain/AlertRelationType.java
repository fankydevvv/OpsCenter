package com.opscenter.incident.domain;

/**
 * How an alert belongs to an incident ({@code incident_alerts.relation_type}, 03-DB §38.3, D-54).
 * <ul>
 *   <li>{@link #TRIGGER} - the alert that opened the incident (also {@code is_primary = true});</li>
 *   <li>{@link #CORRELATED} - a later alert of the same group (same correlation key);</li>
 *   <li>{@link #DUPLICATE_CONTEXT} - reserved for manual linking of look-alike alerts (later sprint).</li>
 * </ul>
 */
public enum AlertRelationType {
    TRIGGER,
    CORRELATED,
    DUPLICATE_CONTEXT
}
