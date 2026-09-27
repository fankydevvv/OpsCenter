package com.opscenter.organization.infrastructure;

import java.util.List;
import java.util.UUID;

import com.opscenter.identity.application.TeamMembershipQuery;
import com.opscenter.identity.application.TeamMembershipView;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements identity's {@link TeamMembershipQuery} port with this module's data.
 * <p>
 * This is the "adapter" half of dependency inversion: identity declared the interface it needs,
 * organization fulfils it, and neither module imports the other's entities. Spring injects this
 * bean wherever identity asks for the port.
 */
@Component
public class TeamMembershipQueryAdapter implements TeamMembershipQuery {

    private final TeamMemberRepository members;

    public TeamMembershipQueryAdapter(TeamMemberRepository members) {
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamMembershipView> membershipsOf(UUID userId) {
        return members.findActiveMembershipsOf(userId).stream()
                .map(m -> new TeamMembershipView(m.getTeam().getId(), m.getTeam().getCode(), m.getTeam().getName(),
                        m.getMemberType().name()))
                .toList();
    }
}
