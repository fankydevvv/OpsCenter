package com.opscenter.alert.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.alert.domain.IngestionOutcome;

/** One notification of an alert on its detail page (blueprint §7.3 {@code occurrences[]}). */
public record AlertOccurrenceView(UUID id, Instant observedAt, AlertStatus status, String sourceEventId,
                                  IngestionOutcome outcome) {
}
