package com.opscenter.incident.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states of an incident (01-SRS §7, 02-SAD §9, 03-DB §38.4).
 * <p>
 * Sprint 2 reaches OPEN, ACKNOWLEDGED, INVESTIGATING, MITIGATED, RESOLVED and REOPENED. ASSIGNED
 * (routing, Sprint 3), VERIFIED and CLOSED (recovery verification, Sprint 4) already exist so the
 * database CHECK, the API contract and the state machine never have to change shape later.
 * <p>
 * "Open" means "somebody still has to work on it": everything except RESOLVED, VERIFIED and
 * CLOSED. Grouping only attaches new alerts to an open incident (D-54), and the partial unique
 * index {@code uk_incidents_open_correlation} uses exactly the same definition.
 */
public enum IncidentStatus {
    OPEN,
    ASSIGNED,
    ACKNOWLEDGED,
    INVESTIGATING,
    MITIGATED,
    RESOLVED,
    VERIFIED,
    CLOSED,
    REOPENED;

    /** The states in which the work is finished (not "open"); must match the SQL of {@code uk_incidents_open_correlation}. */
    public static final Set<IncidentStatus> NOT_OPEN = EnumSet.of(RESOLVED, VERIFIED, CLOSED);

    public boolean isOpen() {
        return !NOT_OPEN.contains(this);
    }

    /** All open states, e.g. for {@code status IN (...)} queries. */
    public static Set<IncidentStatus> openStates() {
        return EnumSet.complementOf(EnumSet.copyOf(NOT_OPEN));
    }
}
