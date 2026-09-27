package com.opscenter.alert.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.opscenter.alert.domain.Alert;
import com.opscenter.alert.domain.AlertOccurrence;
import com.opscenter.alert.infrastructure.AlertOccurrenceRepository;
import com.opscenter.incident.application.IncidentLinkLookup;
import com.opscenter.incident.application.IncidentRef;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.servicecatalog.application.ServiceLookup;
import com.opscenter.servicecatalog.application.ServiceRef;
import com.opscenter.shared.application.storage.StoredObjectRef;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns alert entities into API DTOs in batches: one {@code ServiceLookup.findRefs} call for the
 * service names of a page and one {@code IncidentLinkLookup} call for their incidents (no N+1).
 */
@Component
public class AlertViews {

    private final ServiceLookup services;
    private final IncidentLinkLookup incidents;
    private final AlertOccurrenceRepository occurrences;
    private final JsonMapper jsonMapper;

    public AlertViews(ServiceLookup services, IncidentLinkLookup incidents, AlertOccurrenceRepository occurrences,
                      JsonMapper jsonMapper) {
        this.services = services;
        this.incidents = incidents;
        this.occurrences = occurrences;
        this.jsonMapper = jsonMapper;
    }

    public List<AlertSummary> summaries(List<Alert> alerts) {
        if (alerts.isEmpty()) {
            return List.of();
        }
        Set<UUID> serviceIds = alerts.stream().map(Alert::getServiceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, ServiceRef> serviceRefs = serviceIds.isEmpty() ? Map.of() : services.findRefs(serviceIds);
        Map<UUID, IncidentRef> incidentRefs = incidents.incidentsOfAlerts(alerts.stream().map(Alert::getId).toList());
        return alerts.stream().map(a -> summary(a, serviceRefs, incidentRefs)).toList();
    }

    public AlertDetail detail(Alert alert) {
        AlertSummary s = summaries(List.of(alert)).getFirst();
        AlertPayloads.RawSlice slice = slice(alert);
        List<AlertOccurrenceView> latest = occurrences.findTop50ByAlertIdOrderByObservedAtDesc(alert.getId()).stream()
                .map(this::occurrence)
                .toList();
        return new AlertDetail(s.id(), s.alertName(), s.fingerprint(), s.severity(), s.status(), s.mappingStatus(),
                s.serviceId(), s.serviceCode(), s.serviceName(), s.environment(), s.instance(), s.summary(),
                s.occurrenceCount(), s.firstSeenAt(), s.lastSeenAt(), s.resolvedAt(), s.primaryIncident(),
                alert.getSourceType(), alert.getExternalAlertId(), slice == null ? null : slice.generatorUrl(),
                slice == null || slice.labels() == null ? Map.of() : slice.labels(),
                slice == null || slice.annotations() == null ? Map.of() : slice.annotations(),
                archiveInfo(slice == null ? null : slice.rawRef()), latest, incidents.linksOfAlert(alert.getId()));
    }

    /** The masked slice stored in {@code alerts.raw_payload}; {@code null} if absent. */
    AlertPayloads.RawSlice slice(Alert alert) {
        return alert.getRawPayload() == null ? null
                : jsonMapper.readValue(alert.getRawPayload(), AlertPayloads.RawSlice.class);
    }

    AlertPayloads.Occurrence occurrencePayload(AlertOccurrence occurrence) {
        return occurrence.getPayload() == null ? null
                : jsonMapper.readValue(occurrence.getPayload(), AlertPayloads.Occurrence.class);
    }

    private AlertOccurrenceView occurrence(AlertOccurrence occurrence) {
        AlertPayloads.Occurrence payload = occurrencePayload(occurrence);
        return new AlertOccurrenceView(occurrence.getId(), occurrence.getObservedAt(), occurrence.getStatus(),
                occurrence.getSourceEventId(), payload == null ? null : payload.outcome());
    }

    private static RawArchiveInfo archiveInfo(StoredObjectRef ref) {
        return ref == null ? RawArchiveInfo.notArchived()
                : new RawArchiveInfo(ref.bucket(), ref.key(), ref.sha256(), ref.sizeBytes(), true);
    }

    private static AlertSummary summary(Alert alert, Map<UUID, ServiceRef> serviceRefs,
                                        Map<UUID, IncidentRef> incidentRefs) {
        ServiceRef service = alert.getServiceId() == null ? null : serviceRefs.get(alert.getServiceId());
        return new AlertSummary(alert.getId(), alert.getAlertName(), alert.getFingerprint(), alert.getSeverity(),
                alert.getStatus(), alert.isMapped() ? MappingStatus.MAPPED : MappingStatus.UNMAPPED,
                alert.getServiceId(), alert.getServiceCode(), service == null ? null : service.name(),
                alert.getEnvironment(), alert.getInstance(), alert.getSummary(), alert.getOccurrenceCount(),
                alert.getFirstSeenAt(), alert.getLastSeenAt(), alert.getResolvedAt(), incidentRefs.get(alert.getId()));
    }
}
