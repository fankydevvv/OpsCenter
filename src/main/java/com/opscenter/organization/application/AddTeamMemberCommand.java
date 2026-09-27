package com.opscenter.organization.application;

import java.util.UUID;

import com.opscenter.organization.domain.MemberType;

/** Input of {@code TeamService.addMember} ({@code POST /teams/{id}/members}, 04-API §5/§21). */
public record AddTeamMemberCommand(UUID userId, MemberType memberType, boolean isPrimary, String teamRole) {
}
