package com.opscenter.incident.domain;

/**
 * Where an incident came from ({@code incidents.source}, 03-DB §12.1). Sprint 2 only creates
 * incidents from Alertmanager alerts; manual creation ({@code POST /incidents}) comes later.
 */
public enum IncidentSource {
    ALERTMANAGER,
    WEBHOOK,
    API,
    MANUAL
}
