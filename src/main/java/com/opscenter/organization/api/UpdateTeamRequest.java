package com.opscenter.organization.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.organization.application.UpdateTeamCommand;
import com.opscenter.organization.domain.MasterDataStatus;

/**
 * Body of {@code PATCH /api/v1/teams/{id}}: partial update; {@code status: INACTIVE} soft-deletes
 * the team (03-DB §29); {@code version} is the optimistic-lock token.
 */
public record UpdateTeamRequest(
        @Size(min = 1, max = 255) String name,
        @Size(max = 2000) String description,
        @Size(max = 30) String teamType,
        Boolean onCallEnabled,
        MasterDataStatus status,
        @NotNull Long version) {

    public UpdateTeamCommand toCommand() {
        return new UpdateTeamCommand(name, description, teamType, onCallEnabled, status, version);
    }
}
