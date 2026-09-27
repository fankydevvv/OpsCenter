package com.opscenter.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.MemberType;
import com.opscenter.organization.domain.Organization;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.organization.domain.Team;
import com.opscenter.organization.infrastructure.OrganizationRepository;
import com.opscenter.organization.infrastructure.TeamRepository;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Business rules of {@link TeamService}: default organization, conflicts, membership, soft delete, audit. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock TeamRepository teams;
    @Mock OrganizationRepository organizations;
    @Mock UserRepository users;
    @Mock IdempotencyService idempotency;
    @Mock AuditRecorder audit;

    private TeamService service;
    private Organization defaultOrg;
    private Team team;
    private User engineer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        defaultOrg = new Organization(UUID.randomUUID(), Organization.DEFAULT_CODE, "Default");
        team = Team.create(defaultOrg.getId(), "PAYMENT", "Team Payment", null, "DEV");
        engineer = User.register("engineer.a", "engineer.a@opscenter.local", "{bcrypt}x", "Engineer A");

        when(organizations.findByCode(Organization.DEFAULT_CODE)).thenReturn(Optional.of(defaultOrg));
        when(organizations.findById(defaultOrg.getId())).thenReturn(Optional.of(defaultOrg));
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(teams.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teams.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(users.findById(engineer.getId())).thenReturn(Optional.of(engineer));
        when(users.findAllById(any())).thenReturn(List.of(engineer));
        when(idempotency.hashOf(any())).thenReturn("hash");
        when(idempotency.execute(any(), any(), any(), any(IdempotentOperation.class)))
                .thenAnswer(inv -> IdempotentResult.created(((IdempotentOperation<TeamDetail>) inv.getArgument(3)).create()));

        service = new TeamService(teams, organizations, users, idempotency, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_withoutOrganization_usesTheDefaultOne_andAudits() {
        IdempotentResult<TeamDetail> result = service.create(
                new CreateTeamCommand("platform", "Team Platform", "infra", "devops", null), null);

        assertThat(result.value().organizationId()).isEqualTo(defaultOrg.getId());
        assertThat(result.value().code()).isEqualTo("PLATFORM");
        assertThat(result.value().teamType()).isEqualTo("DEVOPS");
        assertThat(result.value().members()).isEmpty();
        verify(audit).record(eq(AuditAction.TEAM_CREATED), eq("Team"), eq(result.value().id()), any(), any());
    }

    @Test
    void create_rejectsDuplicateCodePerOrganization_andUnknownOrganization() {
        when(teams.existsByOrganizationIdAndCode(defaultOrg.getId(), "PAYMENT")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CreateTeamCommand("payment", "Again", null, null, null), null))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_CODE_TAKEN);

        UUID unknownOrg = UUID.randomUUID();
        when(organizations.findById(unknownOrg)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new CreateTeamCommand("X", "X", null, null, unknownOrg), null))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.ORGANIZATION_NOT_FOUND);
        verify(teams, never()).save(any());
    }

    @Test
    void create_underAnInactiveOrganization_isRefused() {
        defaultOrg.changeStatus(MasterDataStatus.INACTIVE, NOW);

        assertThatThrownBy(() -> service.create(new CreateTeamCommand("X", "X", null, null, null), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.ORGANIZATION_INACTIVE);
        verify(teams, never()).save(any());
    }

    @Test
    void addMember_addsUserWithNamesFromIdentity_andAudits() {
        TeamDetail detail = service.addMember(team.getId(),
                new AddTeamMemberCommand(engineer.getId(), MemberType.PRIMARY, true, "lead"));

        assertThat(detail.members()).hasSize(1);
        TeamMemberView member = detail.members().get(0);
        assertThat(member.userId()).isEqualTo(engineer.getId());
        assertThat(member.username()).isEqualTo("engineer.a");
        assertThat(member.displayName()).isEqualTo("Engineer A");
        assertThat(member.memberType()).isEqualTo(MemberType.PRIMARY);
        assertThat(member.isPrimary()).isTrue();
        assertThat(member.joinedAt()).isEqualTo(NOW);
        verify(audit).record(eq(AuditAction.TEAM_MEMBER_ADDED), eq("Team"), eq(team.getId()), any(), any());
    }

    @Test
    void addMember_twice_isAConflict_andUnknownOrDeletedUserIsNotFound() {
        service.addMember(team.getId(), new AddTeamMemberCommand(engineer.getId(), MemberType.PRIMARY, false, null));
        assertThatThrownBy(() -> service.addMember(team.getId(),
                new AddTeamMemberCommand(engineer.getId(), MemberType.SECONDARY, false, null)))
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_MEMBER_EXISTS);

        UUID ghost = UUID.randomUUID();
        when(users.findById(ghost)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addMember(team.getId(), new AddTeamMemberCommand(ghost, MemberType.PRIMARY, false, null)))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_FOUND);

        engineer.disable(NOW);
        team.removeMember(engineer.getId());
        assertThatThrownBy(() -> service.addMember(team.getId(),
                new AddTeamMemberCommand(engineer.getId(), MemberType.PRIMARY, false, null)))
                .hasFieldOrPropertyWithValue("code", IdentityErrorCodes.USER_NOT_FOUND);
    }

    @Test
    void removeMember_ofNonMember_isNotFound_andRemovalIsAudited() {
        assertThatThrownBy(() -> service.removeMember(team.getId(), engineer.getId()))
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_MEMBER_NOT_FOUND);

        team.addMember(engineer.getId(), MemberType.PRIMARY, false, null, NOW);
        service.removeMember(team.getId(), engineer.getId());

        assertThat(team.getMembers()).isEmpty();
        verify(audit).record(eq(AuditAction.TEAM_MEMBER_REMOVED), eq("Team"), eq(team.getId()), any(), any());
    }

    @Test
    void update_inactiveStatus_isTheSoftDelete_andThenRefusesMembers() {
        TeamDetail after = service.update(team.getId(),
                new UpdateTeamCommand("Payment (archived)", null, null, false, MasterDataStatus.INACTIVE, 0));

        assertThat(after.status()).isEqualTo(MasterDataStatus.INACTIVE);
        assertThat(after.name()).isEqualTo("Payment (archived)");
        assertThat(team.getDeletedAt()).isEqualTo(NOW);
        ArgumentCaptor<Object> before = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(AuditAction.TEAM_UPDATED), eq("Team"), eq(team.getId()), before.capture(), any());
        assertThat(((TeamDetail) before.getValue()).status()).isEqualTo(MasterDataStatus.ACTIVE);

        assertThatThrownBy(() -> service.addMember(team.getId(),
                new AddTeamMemberCommand(engineer.getId(), MemberType.PRIMARY, false, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_INACTIVE);
    }

    @Test
    void update_withStaleVersion_isRejected() {
        assertThatThrownBy(() -> service.update(team.getId(), new UpdateTeamCommand("X", null, null, null, null, 3)))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.CONCURRENCY_VERSION_CONFLICT);
        assertThat(team.getName()).isEqualTo("Team Payment");
    }

    @Test
    void get_unknownTeam_isNotFound() {
        UUID unknown = UUID.randomUUID();
        when(teams.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(unknown))
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_NOT_FOUND);
    }
}
