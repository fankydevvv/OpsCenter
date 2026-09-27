package com.opscenter.servicecatalog.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.servicecatalog.application.AdditionalOwnerSpec;
import com.opscenter.servicecatalog.application.ReplaceOwnershipCommand;
import com.opscenter.servicecatalog.domain.OwnershipType;

/**
 * Body of {@code PUT /api/v1/services/{id}/ownership} (blueprint D-35): the complete ownership.
 * A {@code null} (or omitted) owner is removed; {@code additionalOwners} replaces the whole list.
 */
public record ReplaceOwnershipRequest(
        UUID owningTeamId,
        UUID primaryOwnerId,
        UUID backupOwnerId,
        @Size(max = 50) List<@NotNull @Valid AdditionalOwner> additionalOwners,
        @NotNull Long version) {

    /** Exactly one of {@code teamId} / {@code userId} (checked by the domain: 400 SERVICE_OWNER_TARGET_INVALID). */
    public record AdditionalOwner(UUID teamId, UUID userId, @NotNull OwnershipType ownershipType,
                                  @Min(1) @Max(100) Integer priority) {
    }

    public ReplaceOwnershipCommand toCommand() {
        List<AdditionalOwnerSpec> owners = additionalOwners == null ? List.of() : additionalOwners.stream()
                .map(o -> new AdditionalOwnerSpec(o.teamId(), o.userId(), o.ownershipType(), o.priority()))
                .toList();
        return new ReplaceOwnershipCommand(owningTeamId, primaryOwnerId, backupOwnerId, owners, version);
    }
}
