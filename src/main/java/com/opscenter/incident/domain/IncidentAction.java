package com.opscenter.incident.domain;

/**
 * Commands a person can apply to an incident (04-API §7, blueprint D-55). Each one is its own
 * endpoint ({@code POST /incidents/{id}/acknowledge} ...) because 04-API §7 forbids changing the
 * status with a generic {@code PATCH}: a dedicated command carries its own permission, its own
 * required fields (root cause for RESOLVE) and its own audit action.
 * <p>
 * The automatic RESOLVED -> REOPENED of a re-fired alert is a <em>system</em> decision and therefore
 * not listed here (see {@link IncidentStateMachine#reopen}).
 */
public enum IncidentAction {
    ACKNOWLEDGE,
    START_INVESTIGATION,
    MITIGATE,
    RESOLVE,
    CLOSE
}
