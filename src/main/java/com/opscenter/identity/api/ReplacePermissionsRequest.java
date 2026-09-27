package com.opscenter.identity.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/roles/{id}/permissions}: the complete new set of permission codes. */
public record ReplacePermissionsRequest(@NotNull List<@NotBlank String> permissionCodes) {
}
