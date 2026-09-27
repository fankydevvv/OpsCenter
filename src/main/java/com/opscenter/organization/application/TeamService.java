package com.opscenter.organization.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.Organization;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.organization.domain.Team;
import com.opscenter.organization.domain.TeamMember;
import com.opscenter.organization.infrastructure.OrganizationRepository;
import com.opscenter.organization.infrastructure.TeamRepository;
import com.opscenter.organization.infrastructure.TeamSpecifications;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.application.Sorting;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team use cases (04-API §5/§21, 01-SRS FR-ORG, blueprint §7.3).
 * <p>
 * Membership changes go through the {@link Team} aggregate so its rules apply, and each mutation
 * writes its audit line in the same transaction (03-DB §27). Creating a team is idempotent via the
 * shared {@link IdempotencyService} (D-13, TC-IDEMP-001 adapted).
 * <p>
 * Cross-module note: this service reads identity's {@link UserRepository} to verify that a member
 * exists and to show usernames. That is the only allowed direction (organization -> identity) and
 * only by id; no entity relation crosses the module boundary.
 */
@Service
public class TeamService {

    private static final String RESOURCE_TYPE = "Team";

    /** Properties a client may sort the team list by (04-API §2.4). */
    static final Set<String> SORTABLE = Set.of("code", "name", "status", "teamType", "createdAt", "updatedAt");

    private final TeamRepository teams;
    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final IdempotencyService idempotency;
    private final AuditRecorder audit;
    private final Clock clock;

    public TeamService(TeamRepository teams, OrganizationRepository organizations, UserRepository users,
                       IdempotencyService idempotency, AuditRecorder audit, Clock clock) {
        this.teams = teams;
        this.organizations = organizations;
        this.users = users;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    // --- queries ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<TeamSummary> list(String query, MasterDataStatus status, UUID organizationId, Pageable pageable) {
        List<Specification<Team>> filters = new ArrayList<>();
        if (organizationId != null) {
            filters.add(TeamSpecifications.inOrganization(organizationId));
        }
        if (status != null) {
            filters.add(TeamSpecifications.hasStatus(status));
        }
        if (query != null && !query.isBlank()) {
            filters.add(TeamSpecifications.matches(query));
        }
        Pageable safe = Sorting.restrict(pageable, SORTABLE, "code");
        return teams.findAll(Specification.allOf(filters), safe).map(TeamSummary::from);
    }

    @Transactional(readOnly = true)
    public TeamDetail get(UUID id) {
        return toDetail(load(id));
    }

    // --- commands ---------------------------------------------------------------------------

    /**
     * Not {@code @Transactional}: {@link IdempotencyService#execute} claims the key first and then
     * opens the business transaction itself (see {@code UserService.create} for the reasoning).
     */
    public IdempotentResult<TeamDetail> create(CreateTeamCommand command, String idempotencyKey) {
        return idempotency.execute(idempotencyKey, idempotency.hashOf(command), RESOURCE_TYPE,
                new IdempotentOperation<>() {
                    @Override
                    public TeamDetail create() {
                        return doCreate(command);
                    }

                    @Override
                    public UUID resourceId(TeamDetail created) {
                        return created.id();
                    }

                    @Override
                    public TeamDetail reload(UUID resourceId) {
                        return get(resourceId);
                    }
                });
    }

    private TeamDetail doCreate(CreateTeamCommand command) {
        Organization organization = resolveOrganization(command.organizationId());
        String code = Team.normalizeCode(command.code());
        if (teams.existsByOrganizationIdAndCode(organization.getId(), code)) {
            throw new ConflictException(OrganizationErrorCodes.TEAM_CODE_TAKEN,
                    "Team code '" + code + "' already exists in organization " + organization.getCode());
        }
        Team team = Team.create(organization.getId(), code, command.name(), command.description(), command.teamType());
        teams.save(team);
        TeamDetail created = toDetail(team);
        audit.record(AuditAction.TEAM_CREATED, RESOURCE_TYPE, team.getId(), null, created);
        return created;
    }

    @Transactional
    public TeamDetail update(UUID id, UpdateTeamCommand command) {
        Team team = load(id);
        team.assertVersion(command.version());
        TeamDetail before = toDetail(team);
        if (command.name() != null) {
            team.rename(command.name());
        }
        if (command.description() != null) {
            team.describe(command.description());
        }
        if (command.teamType() != null) {
            team.changeType(command.teamType());
        }
        if (command.onCallEnabled() != null) {
            team.setOnCallEnabled(command.onCallEnabled());
        }
        if (command.status() != null) {
            team.changeStatus(command.status(), clock.instant());
        }
        teams.saveAndFlush(team);
        TeamDetail after = toDetail(team);
        audit.record(AuditAction.TEAM_UPDATED, RESOURCE_TYPE, team.getId(), before, after);
        return after;
    }

    @Transactional
    public TeamDetail addMember(UUID teamId, AddTeamMemberCommand command) {
        Team team = load(teamId);
        User user = users.findById(command.userId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new NotFoundException(IdentityErrorCodes.USER_NOT_FOUND,
                        "User " + command.userId() + " not found"));
        TeamDetail before = toDetail(team);
        team.addMember(user.getId(), command.memberType(), command.isPrimary(), command.teamRole(), clock.instant());
        teams.saveAndFlush(team);
        TeamDetail after = toDetail(team);
        audit.record(AuditAction.TEAM_MEMBER_ADDED, RESOURCE_TYPE, team.getId(), before, after);
        return after;
    }

    @Transactional
    public void removeMember(UUID teamId, UUID userId) {
        Team team = load(teamId);
        TeamDetail before = toDetail(team);
        team.removeMember(userId);
        teams.saveAndFlush(team);
        audit.record(AuditAction.TEAM_MEMBER_REMOVED, RESOURCE_TYPE, team.getId(), before, toDetail(team));
    }

    // --- helpers ----------------------------------------------------------------------------

    private Team load(UUID id) {
        return teams.findById(id)
                .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.TEAM_NOT_FOUND,
                        "Team " + id + " not found"));
    }

    /** A soft-deleted organization (03-DB §29) cannot receive new teams, like an inactive team cannot receive members. */
    private Organization resolveOrganization(UUID organizationId) {
        Organization organization;
        if (organizationId == null) {
            organization = organizations.findByCode(Organization.DEFAULT_CODE)
                    .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.ORGANIZATION_NOT_FOUND,
                            "Default organization is not seeded"));
        }
        else {
            organization = organizations.findById(organizationId)
                    .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.ORGANIZATION_NOT_FOUND,
                            "Organization " + organizationId + " not found"));
        }
        if (!organization.isActive()) {
            throw new BusinessRuleException(OrganizationErrorCodes.ORGANIZATION_INACTIVE,
                    "Organization " + organization.getCode() + " is inactive");
        }
        return organization;
    }

    /** Builds the detail DTO, enriching members with names from identity in one query. */
    private TeamDetail toDetail(Team team) {
        List<TeamMember> members = team.getMembers().stream()
                .sorted(Comparator.comparing(TeamMember::getJoinedAt).thenComparing(TeamMember::getUserId))
                .toList();
        List<UUID> userIds = members.stream().map(TeamMember::getUserId).toList();
        Map<UUID, User> byId = userIds.isEmpty() ? Map.of()
                : users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<TeamMemberView> views = members.stream()
                .map(m -> {
                    User user = byId.get(m.getUserId());
                    return new TeamMemberView(m.getUserId(), user == null ? null : user.getUsername(),
                            user == null ? null : user.getDisplayName(), m.getMemberType(), m.isPrimary(),
                            m.getTeamRole(), m.getJoinedAt());
                })
                .toList();
        return TeamDetail.from(team, views);
    }
}
