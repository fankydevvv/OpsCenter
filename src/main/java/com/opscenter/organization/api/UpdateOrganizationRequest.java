package com.opscenter.organization.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.organization.application.UpdateOrganizationCommand;
import com.opscenter.organization.domain.MasterDataStatus;

/** Body of {@code PATCH /api/v1/organizations/{id}}; omitted fields stay unchanged. */
public record UpdateOrganizationRequest(
        @Size(min = 1, max = 255) String name,
        MasterDataStatus status,
        @NotNull Long version) {

    public UpdateOrganizationCommand toCommand() {
        return new UpdateOrganizationCommand(name, status, version);
    }
}
