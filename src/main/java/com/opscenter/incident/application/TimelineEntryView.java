package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.incident.domain.TimelineSource;

import tools.jackson.databind.JsonNode;

/** One entry of {@code GET /incidents/{id}/timeline} (blueprint §7.4); {@code actor = null} for system/source events. */
public record TimelineEntryView(UUID id, String eventType, TimelineSource source, UserBrief actor, String title,
                                String description, Instant eventAt, JsonNode metadata) {
}
