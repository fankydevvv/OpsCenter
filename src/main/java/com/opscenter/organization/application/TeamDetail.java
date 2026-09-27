package com.opscenter.organization.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.Team;

/**
 * Full representation of a team with its members (blueprint §7.3). Also the audit snapshot of
 * {@code TEAM_*} actions; contains no credentials by construction.
 */
public record TeamDetail(UUID id, UUID organizationId, String code, String name, String description, String teamType,
                         boolean onCallEnabled, MasterDataStatus status, List<TeamMemberView> members,
                         Instant createdAt, Instant updatedAt, long version) {

    public static TeamDetail from(Team team, List<TeamMemberView> members) {
        return new TeamDetail(team.getId(), team.getOrganizationId(), team.getCode(), team.getName(),
                team.getDescription(), team.getTeamType(), team.isOnCallEnabled(), team.getStatus(), members,
                team.getCreatedAt(), team.getUpdatedAt(), team.getVersion());
    }
}
