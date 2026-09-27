package com.opscenter.incident.application;

import java.time.Instant;

import com.opscenter.incident.domain.IncidentStatus;

/** One row of the status history; {@code changedBy = null} is a system transition. */
public record StatusHistoryView(IncidentStatus fromStatus, IncidentStatus toStatus, UserBrief changedBy,
                                Instant changedAt, String reason) {
}
