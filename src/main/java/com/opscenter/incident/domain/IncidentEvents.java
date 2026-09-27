package com.opscenter.incident.domain;

/**
 * Names of the domain events the incident module appends to the transactional outbox
 * (blueprint §9.1, 04-API §15). The routing key is derived from the name by the relay
 * ({@code IncidentInvestigationStarted -> incident.investigation.started}, base D-14).
 * Consumers (notifications, WebSocket gateway) arrive in Sprint 3.
 */
public final class IncidentEvents {

    public static final String AGGREGATE_TYPE = "Incident";

    public static final String INCIDENT_CREATED = "IncidentCreated";
    /** Non-status change: ALERT_LINKED, SEVERITY_RAISED, ALL_ALERTS_RESOLVED or SERVICE_RESOLVED. */
    public static final String INCIDENT_UPDATED = "IncidentUpdated";
    public static final String INCIDENT_ACKNOWLEDGED = "IncidentAcknowledged";
    public static final String INCIDENT_INVESTIGATION_STARTED = "IncidentInvestigationStarted";
    public static final String INCIDENT_MITIGATED = "IncidentMitigated";
    public static final String INCIDENT_RESOLVED = "IncidentResolved";
    public static final String INCIDENT_CLOSED = "IncidentClosed";
    public static final String INCIDENT_REOPENED = "IncidentReopened";

    /** Values of the {@code change} field of {@link #INCIDENT_UPDATED}. */
    public static final String CHANGE_ALERT_LINKED = "ALERT_LINKED";
    public static final String CHANGE_SEVERITY_RAISED = "SEVERITY_RAISED";
    public static final String CHANGE_ALL_ALERTS_RESOLVED = "ALL_ALERTS_RESOLVED";
    public static final String CHANGE_SERVICE_RESOLVED = "SERVICE_RESOLVED";

    private IncidentEvents() {
    }
}
