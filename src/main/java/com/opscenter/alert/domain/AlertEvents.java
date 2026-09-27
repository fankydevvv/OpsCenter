package com.opscenter.alert.domain;

/**
 * Names of the alert module's outbox events (blueprint §9.1). Deduplicated notifications publish
 * nothing - they only move a counter.
 */
public final class AlertEvents {

    public static final String AGGREGATE_TYPE = "Alert";

    /** A new logical alert (CREATED or REFIRED) - routing key {@code alert.received}. */
    public static final String ALERT_RECEIVED = "AlertReceived";
    /** A FIRING alert became RESOLVED - routing key {@code alert.resolved}. */
    public static final String ALERT_RESOLVED = "AlertResolved";

    private AlertEvents() {
    }
}
