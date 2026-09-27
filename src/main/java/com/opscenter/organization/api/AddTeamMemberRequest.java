package com.opscenter.organization.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.organization.application.AddTeamMemberCommand;
import com.opscenter.organization.domain.MemberType;

/** Body of {@code POST /api/v1/teams/{id}/members} (04-API §5/§21, 03-DB §39.2). */
public record AddTeamMemberRequest(
        @NotNull UUID userId,
        @NotNull MemberType memberType,
        Boolean isPrimary,
        @Size(max = 50) String teamRole) {

    public AddTeamMemberCommand toCommand() {
        return new AddTeamMemberCommand(userId, memberType, Boolean.TRUE.equals(isPrimary), teamRole);
    }
}
