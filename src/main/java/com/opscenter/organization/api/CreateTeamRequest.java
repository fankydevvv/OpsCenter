package com.opscenter.organization.api;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.organization.application.CreateTeamCommand;

/** Body of {@code POST /api/v1/teams} (blueprint §7.3); {@code organizationId} defaults to DEFAULT. */
public record CreateTeamRequest(
        @NotBlank @Size(min = 2, max = 100)
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$", message = "must start with a letter and contain letters, digits, '_' or '-' only")
        String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @Size(max = 30) String teamType,
        UUID organizationId) {

    public CreateTeamCommand toCommand() {
        return new CreateTeamCommand(code, name, description, teamType, organizationId);
    }
}
