package com.opscenter.incident.application;

import java.util.UUID;

/**
 * Answer of {@code IncidentCorrelationService.correlate}: which incident the alert ended up in and how.
 *
 * @param action {@code CREATED} (new incident), {@code LINKED} (joined an open one) or
 *               {@code REOPENED} (a recently resolved incident came back, D-54)
 */
public record CorrelationResult(UUID incidentId, String incidentNo, Action action) {

    public enum Action {
        CREATED,
        LINKED,
        REOPENED
    }
}
