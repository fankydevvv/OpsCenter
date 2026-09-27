package com.opscenter.alert.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.servicecatalog.application.MappingStatus;
import com.opscenter.shared.domain.Severity;

/**
 * Filters of {@code GET /api/v1/alerts} (blueprint §7.3). All optional; {@code from}/{@code to}
 * apply to {@code lastSeenAt}; {@code incidentId} = alerts linked to that incident.
 */
public record AlertListQuery(List<AlertStatus> statuses, List<Severity> severities, UUID serviceId,
                             String environment, MappingStatus mappingStatus, AlertSourceType sourceType, String q,
                             Instant from, Instant to, UUID incidentId) {

    public AlertListQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        severities = severities == null ? List.of() : List.copyOf(severities);
    }
}
