package com.opscenter.incident.domain;

/**
 * Error codes of the incident module (04-API §7.2, §17; blueprint §7.7, D-56).
 */
public final class IncidentErrorCodes {

    /** 404 */
    public static final String INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND";
    /** 409 - the command is not allowed in the current status (04-API §17 "invalid state conflict"). */
    public static final String INCIDENT_INVALID_TRANSITION = "INCIDENT_INVALID_TRANSITION";
    /** 409 - the client sent a stale {@code version} (04-API §7.2, TC-INC-010). */
    public static final String INCIDENT_VERSION_CONFLICT = "INCIDENT_VERSION_CONFLICT";
    /** 422 - closing needs a PASSED recovery verification (03-DB §38.4); verification arrives in Sprint 4. */
    public static final String RECOVERY_VERIFICATION_REQUIRED = "RECOVERY_VERIFICATION_REQUIRED";

    private IncidentErrorCodes() {
    }
}
