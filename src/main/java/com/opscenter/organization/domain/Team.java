package com.opscenter.organization.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.hibernate.annotations.BatchSize;

/**
 * A team and its members - the aggregate root of this module ({@code teams} +
 * {@code team_members}, 03-DB §6.2/§6.3, §39.2; 01-SRS FR-ORG).
 * <p>
 * Membership rules are methods on the aggregate, not checks scattered across services: a user
 * joins once ({@code TEAM_MEMBER_EXISTS}), only an active team accepts members
 * ({@code TEAM_INACTIVE}), and removing a stranger is {@code TEAM_MEMBER_NOT_FOUND}.
 * {@code cascade + orphanRemoval} let Hibernate persist/delete member rows as the set changes,
 * so the service only ever saves the team. Soft delete = {@link #deactivate(Instant)} (03-DB §29).
 */
@Entity
@Table(name = "teams")
public class Team extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "team_type", length = 30)
    private String teamType;

    @Column(name = "on_call_enabled", nullable = false)
    private boolean onCallEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MasterDataStatus status;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "team", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private Set<TeamMember> members = new HashSet<>();

    protected Team() {
    }

    private Team(UUID id, UUID organizationId, String code, String name, String description, String teamType) {
        super(id);
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.teamType = teamType;
        this.status = MasterDataStatus.ACTIVE;
        this.active = true;
    }

    public static Team create(UUID organizationId, String code, String name, String description, String teamType) {
        return new Team(UUID.randomUUID(), Objects.requireNonNull(organizationId, "organizationId"),
                normalizeCode(code), Objects.requireNonNull(name, "name").trim(), description, normalizeType(teamType));
    }

    /** Team codes are upper-case identifiers ({@code PAYMENT}); unique per organization. */
    public static String normalizeCode(String code) {
        return Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeType(String teamType) {
        return teamType == null || teamType.isBlank() ? null : teamType.trim().toUpperCase(Locale.ROOT);
    }

    // --- membership -------------------------------------------------------------------------

    public TeamMember addMember(UUID userId, MemberType memberType, boolean primary, String teamRole, Instant now) {
        if (!active) {
            throw new BusinessRuleException(OrganizationErrorCodes.TEAM_INACTIVE,
                    "Team " + code + " is inactive; reactivate it before adding members");
        }
        if (member(userId).isPresent()) {
            throw new ConflictException(OrganizationErrorCodes.TEAM_MEMBER_EXISTS,
                    "User " + userId + " is already a member of team " + code);
        }
        TeamMember member = new TeamMember(this, userId, memberType, primary, teamRole, now);
        members.add(member);
        return member;
    }

    public TeamMember removeMember(UUID userId) {
        TeamMember member = member(userId)
                .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.TEAM_MEMBER_NOT_FOUND,
                        "User " + userId + " is not a member of team " + code));
        members.remove(member);
        return member;
    }

    public Optional<TeamMember> member(UUID userId) {
        return members.stream().filter(m -> m.getUserId().equals(userId)).findFirst();
    }

    // --- profile & lifecycle ----------------------------------------------------------------

    public void rename(String newName) {
        this.name = Objects.requireNonNull(newName, "name").trim();
    }

    public void describe(String newDescription) {
        this.description = newDescription;
    }

    public void changeType(String newTeamType) {
        this.teamType = normalizeType(newTeamType);
    }

    public void setOnCallEnabled(boolean enabled) {
        this.onCallEnabled = enabled;
    }

    /** Soft delete (03-DB §29): keeps history and member rows, hides the team from routing. */
    public void deactivate(Instant now) {
        this.status = MasterDataStatus.INACTIVE;
        this.active = false;
        this.deletedAt = now;
    }

    public void activate() {
        this.status = MasterDataStatus.ACTIVE;
        this.active = true;
        this.deletedAt = null;
    }

    public void changeStatus(MasterDataStatus newStatus, Instant now) {
        if (newStatus == MasterDataStatus.INACTIVE) {
            deactivate(now);
        }
        else {
            activate();
        }
    }

    // --- getters ----------------------------------------------------------------------------

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTeamType() {
        return teamType;
    }

    public boolean isOnCallEnabled() {
        return onCallEnabled;
    }

    public MasterDataStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Set<TeamMember> getMembers() {
        return Set.copyOf(members);
    }
}
