package com.opscenter.incident.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /incidents/{id}/resolve} (04-API §7.3). Root cause and resolution are
 * mandatory (TC-INC-006); {@code mitigation} optionally replaces the mitigation summary;
 * {@code evidenceIds} is accepted for contract compatibility and ignored until evidence exists
 * (Sprint 4).
 */
public record ResolveIncidentRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 4000) String rootCause,
        @NotBlank @Size(max = 4000) String resolution,
        @Size(max = 4000) String mitigation,
        List<UUID> evidenceIds) {
}
