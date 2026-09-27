package com.opscenter.organization.application;

import java.util.UUID;

import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.Team;

/** One row of {@code GET /api/v1/teams} (blueprint §7.3). */
public record TeamSummary(UUID id, String code, String name, MasterDataStatus status, String teamType,
                          boolean onCallEnabled, int memberCount) {

    public static TeamSummary from(Team team) {
        return new TeamSummary(team.getId(), team.getCode(), team.getName(), team.getStatus(), team.getTeamType(),
                team.isOnCallEnabled(), team.getMembers().size());
    }
}
