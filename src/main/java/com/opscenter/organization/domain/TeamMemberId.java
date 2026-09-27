package com.opscenter.organization.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Composite key of {@code team_members} ({@code PRIMARY KEY (team_id, user_id)}, 03-DB §6.3).
 * A user can be in a team only once; the database enforces it and {@code Team.addMember} checks
 * it first to answer a clean {@code 409 TEAM_MEMBER_EXISTS}.
 */
@Embeddable
public class TeamMemberId implements Serializable {

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    protected TeamMemberId() {
    }

    public TeamMemberId(UUID teamId, UUID userId) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeamMemberId that)) {
            return false;
        }
        return Objects.equals(teamId, that.teamId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, userId);
    }
}
