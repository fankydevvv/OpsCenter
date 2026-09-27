package com.opscenter.incident.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The incident module's public read API for the alert module (D-37): "which incident does this alert
 * belong to?". The alert list shows it as {@code primaryIncident}, the alert detail lists every link,
 * and the {@code incidentId} filter of {@code GET /alerts} needs the alert ids of an incident.
 */
public interface IncidentLinkLookup {

    /** For each alert id the incident it was (most recently) linked to; alerts without incident are absent. */
    Map<UUID, IncidentRef> incidentsOfAlerts(Collection<UUID> alertIds);

    /** Every incident the alert is linked to, oldest link first. */
    List<AlertIncidentLink> linksOfAlert(UUID alertId);

    /** Alert ids linked to an incident (empty for an unknown incident). */
    List<UUID> alertIdsOfIncident(UUID incidentId);
}
