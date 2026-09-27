package com.opscenter.incident.application;

import java.util.UUID;

import com.opscenter.incident.domain.IncidentStatus;

/**
 * The smallest useful reference to an incident: what an alert row shows as its
 * {@code primaryIncident} (blueprint §7.3) and what the webhook summary reports per item.
 */
public record IncidentRef(UUID id, String incidentNo, IncidentStatus status) {
}
