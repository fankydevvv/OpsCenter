package com.opscenter.organization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Membership of one user in one team ({@code team_members}, 03-DB §6.3, §39.2).
 * <p>
 * Part of the {@link Team} aggregate: it is created and removed only through
 * {@code Team.addMember/removeMember}, never saved on its own, so the invariants stay in one
 * place. The user is referenced by id only - a deliberate module boundary (no JPA relation into
 * identity). {@code @MapsId("teamId")} shares the {@code team_id} column between the embedded key
 * and the owning relation.
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @MapsId("teamId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_type", nullable = false, length = 20)
    private MemberType memberType;

    @Column(name = "team_role", length = 50)
    private String teamRole;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected TeamMember() {
    }

    TeamMember(Team team, UUID userId, MemberType memberType, boolean primary, String teamRole, Instant joinedAt) {
        this.team = Objects.requireNonNull(team, "team");
        this.id = new TeamMemberId(team.getId(), userId);
        this.memberType = Objects.requireNonNull(memberType, "memberType");
        this.primary = primary;
        this.teamRole = teamRole;
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt");
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public Team getTeam() {
        return team;
    }

    public MemberType getMemberType() {
        return memberType;
    }

    public String getTeamRole() {
        return teamRole;
    }

    public boolean isPrimary() {
        return primary;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof TeamMember that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return TeamMember.class.hashCode();
    }
}
