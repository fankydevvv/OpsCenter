package com.opscenter.alert.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.incident.application.AlertIncidentLink;
import com.opscenter.incident.application.IncidentRef;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.domain.Severity;

/**
 * {@code GET /api/v1/alerts/{id}} (blueprint §7.3): the summary fields plus labels/annotations of the
 * latest notification (sensitive values masked), the raw-archive reference, the 50 newest
 * occurrences and every incident the alert belongs to.
 */
public record AlertDetail(UUID id, String alertName, String fingerprint, Severity severity, AlertStatus status,
                          MappingStatus mappingStatus, UUID serviceId, String serviceCode, String serviceName,
                          String environment, String instance, String summary, long occurrenceCount,
                          Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt, IncidentRef primaryIncident,
                          AlertSourceType sourceType, String externalAlertId, String generatorUrl,
                          Map<String, String> labels, Map<String, String> annotations, RawArchiveInfo rawArchive,
                          List<AlertOccurrenceView> occurrences, List<AlertIncidentLink> incidents) {
}
