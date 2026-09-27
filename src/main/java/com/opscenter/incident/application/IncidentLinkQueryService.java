package com.opscenter.incident.application;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentAlert;
import com.opscenter.incident.infrastructure.IncidentAlertRepository;
import com.opscenter.incident.infrastructure.IncidentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link IncidentLinkLookup} - two batch queries per call, no N+1. */
@Service
public class IncidentLinkQueryService implements IncidentLinkLookup {

    private final IncidentAlertRepository incidentAlerts;
    private final IncidentRepository incidents;

    public IncidentLinkQueryService(IncidentAlertRepository incidentAlerts, IncidentRepository incidents) {
        this.incidentAlerts = incidentAlerts;
        this.incidents = incidents;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, IncidentRef> incidentsOfAlerts(Collection<UUID> alertIds) {
        if (alertIds == null || alertIds.isEmpty()) {
            return Map.of();
        }
        List<IncidentAlert> links = incidentAlerts.findByIdAlertIdIn(alertIds);
        Map<UUID, Incident> byId = load(links);
        Map<UUID, IncidentRef> result = new HashMap<>();
        links.stream()
                .sorted(Comparator.comparing(IncidentAlert::getLinkedAt))
                .forEach(link -> {
                    Incident incident = byId.get(link.getIncidentId());
                    if (incident != null) {
                        // later links overwrite earlier ones: the most recent incident wins
                        result.put(link.getAlertId(),
                                new IncidentRef(incident.getId(), incident.getIncidentNo(), incident.getStatus()));
                    }
                });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertIncidentLink> linksOfAlert(UUID alertId) {
        List<IncidentAlert> links = incidentAlerts.findByIdAlertIdIn(List.of(alertId));
        Map<UUID, Incident> byId = load(links);
        return links.stream()
                .sorted(Comparator.comparing(IncidentAlert::getLinkedAt))
                .filter(link -> byId.containsKey(link.getIncidentId()))
                .map(link -> {
                    Incident incident = byId.get(link.getIncidentId());
                    return new AlertIncidentLink(incident.getId(), incident.getIncidentNo(), incident.getStatus(),
                            incident.getSeverity(), link.getRelationType(), link.isPrimary(), link.getLinkedAt());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> alertIdsOfIncident(UUID incidentId) {
        return incidentAlerts.findAlertIdsByIncidentId(incidentId);
    }

    private Map<UUID, Incident> load(List<IncidentAlert> links) {
        List<UUID> ids = links.stream().map(IncidentAlert::getIncidentId).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : incidents.findAllById(ids).stream().collect(Collectors.toMap(Incident::getId, Function.identity()));
    }
}
