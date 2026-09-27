package com.opscenter.incident.domain;

/**
 * Audit actions of the incident module (blueprint §9.2, 01-SRS §15, TC-AUD-003). Every status
 * change is audited in the same transaction as the change itself.
 */
public final class IncidentAuditActions {

    public static final String INCIDENT_CREATED = "INCIDENT_CREATED";
    public static final String INCIDENT_ACKNOWLEDGED = "INCIDENT_ACKNOWLEDGED";
    public static final String INCIDENT_INVESTIGATION_STARTED = "INCIDENT_INVESTIGATION_STARTED";
    public static final String INCIDENT_MITIGATED = "INCIDENT_MITIGATED";
    public static final String INCIDENT_RESOLVED = "INCIDENT_RESOLVED";
    /** Sprint 4 (CLOSE from VERIFIED); present so the command table is complete. */
    public static final String INCIDENT_CLOSED = "INCIDENT_CLOSED";
    public static final String INCIDENT_REOPENED = "INCIDENT_REOPENED";
    public static final String INCIDENT_SEVERITY_CHANGED = "INCIDENT_SEVERITY_CHANGED";
    /** An [UNMAPPED] incident was attached to the service registered after it was opened (D-48/D-49). */
    public static final String INCIDENT_SERVICE_RESOLVED = "INCIDENT_SERVICE_RESOLVED";

    private IncidentAuditActions() {
    }
}
