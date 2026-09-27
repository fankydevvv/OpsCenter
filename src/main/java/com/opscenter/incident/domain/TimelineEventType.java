package com.opscenter.incident.domain;

/**
 * Catalogue of {@code incident_timeline.event_type} values (blueprint §9.3). Constants rather than
 * an enum on purpose: the column has no CHECK because later sprints add their own entries
 * (assignment, notification sent, verification ...) without a migration.
 */
public final class TimelineEventType {

    public static final String INCIDENT_CREATED = "INCIDENT_CREATED";
    /** The alert's service code is not in the catalog (D-49). */
    public static final String SERVICE_UNRESOLVED = "SERVICE_UNRESOLVED";
    /** A later alert of the group resolved to a catalog service; the incident adopted it (D-48). */
    public static final String SERVICE_RESOLVED = "SERVICE_RESOLVED";
    /** The service exists but the alert's environment is not registered for it (D-48). */
    public static final String ENVIRONMENT_NOT_REGISTERED = "ENVIRONMENT_NOT_REGISTERED";
    /** The alert carried no/unknown severity label; the default was applied (D-45). */
    public static final String SEVERITY_DEFAULTED = "SEVERITY_DEFAULTED";
    public static final String ALERT_LINKED = "ALERT_LINKED";
    public static final String SEVERITY_RAISED = "SEVERITY_RAISED";
    public static final String ALERT_RESOLVED = "ALERT_RESOLVED";
    public static final String ALL_ALERTS_RESOLVED = "ALL_ALERTS_RESOLVED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String INCIDENT_REOPENED = "INCIDENT_REOPENED";

    private TimelineEventType() {
    }
}
