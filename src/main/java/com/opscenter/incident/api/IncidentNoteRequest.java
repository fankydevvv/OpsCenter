package com.opscenter.incident.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code acknowledge}, {@code start-investigation} and {@code close} (04-API §7.2):
 * the {@code version} the client worked on (optimistic lock, D-57) and an optional note that
 * becomes the reason of the status-history line.
 */
public record IncidentNoteRequest(
        @NotNull @PositiveOrZero Long version,
        @Size(max = 4000) String note) {
}
