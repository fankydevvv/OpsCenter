package com.opscenter.organization.infrastructure;

import java.util.List;
import java.util.UUID;

import com.opscenter.organization.domain.TeamMember;
import com.opscenter.organization.domain.TeamMemberId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-side access to {@code team_members}. Writes go through the {@code Team} aggregate
 * (cascade), so this repository only answers "which teams is this user in".
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {

    /** Memberships of a user in active teams, team loaded in the same query to avoid N+1. */
    @Query("select m from TeamMember m join fetch m.team t "
            + "where m.id.userId = :userId and t.active = true order by t.code")
    List<TeamMember> findActiveMembershipsOf(@Param("userId") UUID userId);
}
