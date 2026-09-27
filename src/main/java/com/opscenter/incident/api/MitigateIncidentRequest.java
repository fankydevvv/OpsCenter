package com.opscenter.incident.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /incidents/{id}/mitigate}: what was done to stop the impact (TC-INC-005). */
public record MitigateIncidentRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 4000) String mitigation) {
}
