package com.opscenter.identity.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/users/{id}/roles}: the complete new set of role codes (D-08). */
public record AssignRolesRequest(@NotNull List<@NotBlank String> roleCodes) {
}
