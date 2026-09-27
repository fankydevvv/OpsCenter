package com.opscenter.organization.domain;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Membership and lifecycle rules of the {@link Team} aggregate (03-DB §6.3, §29, §39.2). */
class TeamTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    @Test
    void create_normalisesCodeAndType_andStartsActiveWithoutMembers() {
        Team team = Team.create(UUID.randomUUID(), " payment ", " Team Payment ", null, "dev");

        assertThat(team.getCode()).isEqualTo("PAYMENT");
        assertThat(team.getName()).isEqualTo("Team Payment");
        assertThat(team.getTeamType()).isEqualTo("DEV");
        assertThat(team.getStatus()).isEqualTo(MasterDataStatus.ACTIVE);
        assertThat(team.isActive()).isTrue();
        assertThat(team.getMembers()).isEmpty();
    }

    @Test
    void addMember_once_thenDuplicateIsAConflict() {
        Team team = Team.create(UUID.randomUUID(), "PAYMENT", "Team Payment", null, null);
        UUID userId = UUID.randomUUID();

        TeamMember member = team.addMember(userId, MemberType.PRIMARY, true, "lead", NOW);

        assertThat(member.getUserId()).isEqualTo(userId);
        assertThat(member.getTeam()).isSameAs(team);
        assertThat(member.isPrimary()).isTrue();
        assertThat(team.member(userId)).isPresent();
        assertThatThrownBy(() -> team.addMember(userId, MemberType.SECONDARY, false, null, NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_MEMBER_EXISTS);
        assertThat(team.getMembers()).hasSize(1);
    }

    @Test
    void removeMember_ofStranger_isNotFound() {
        Team team = Team.create(UUID.randomUUID(), "PAYMENT", "Team Payment", null, null);
        UUID userId = UUID.randomUUID();
        team.addMember(userId, MemberType.ON_CALL, false, null, NOW);

        team.removeMember(userId);
        assertThat(team.getMembers()).isEmpty();
        assertThatThrownBy(() -> team.removeMember(userId))
                .isInstanceOf(NotFoundException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_MEMBER_NOT_FOUND);
    }

    @Test
    void deactivate_isTheSoftDelete_andBlocksNewMembers() {
        Team team = Team.create(UUID.randomUUID(), "PAYMENT", "Team Payment", null, null);

        team.changeStatus(MasterDataStatus.INACTIVE, NOW);

        assertThat(team.getStatus()).isEqualTo(MasterDataStatus.INACTIVE);
        assertThat(team.isActive()).isFalse();
        assertThat(team.getDeletedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> team.addMember(UUID.randomUUID(), MemberType.PRIMARY, false, null, NOW))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", OrganizationErrorCodes.TEAM_INACTIVE);

        team.changeStatus(MasterDataStatus.ACTIVE, NOW);
        assertThat(team.isActive()).isTrue();
        assertThat(team.getDeletedAt()).isNull();
    }
}
