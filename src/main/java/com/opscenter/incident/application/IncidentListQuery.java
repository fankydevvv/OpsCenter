package com.opscenter.incident.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentSource;
import com.opscenter.incident.domain.IncidentStatus;
import com.opscenter.shared.domain.Severity;

/**
 * Filters of {@code GET /api/v1/incidents} (blueprint §7.4). Every field is optional; lists may be
 * empty. {@code open} and {@code unmapped} are tri-state: {@code null} = do not filter.
 */
public record IncidentListQuery(List<IncidentStatus> statuses, List<Severity> severities, Boolean open,
                                UUID serviceId, Boolean unmapped, String environment, UUID owningTeamId,
                                UUID assigneeId, IncidentSource source, String q, Instant from, Instant to) {

    public IncidentListQuery {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        severities = severities == null ? List.of() : List.copyOf(severities);
    }
}
