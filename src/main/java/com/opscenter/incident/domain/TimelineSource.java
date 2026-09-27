package com.opscenter.incident.domain;

/**
 * Who produced a timeline entry ({@code incident_timeline.source}, 03-DB §13.3): a person
 * ({@link #USER}), an automatic decision of OpsCenter ({@link #SYSTEM}, e.g. REOPENED) or an
 * external source whose event is being reported ({@link #ALERTMANAGER} ...).
 */
public enum TimelineSource {
    USER,
    SYSTEM,
    ALERTMANAGER,
    WEBHOOK,
    API
}
