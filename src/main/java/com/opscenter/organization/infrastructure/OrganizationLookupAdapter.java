package com.opscenter.organization.infrastructure;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.organization.application.OrganizationLookup;
import com.opscenter.organization.application.TeamLookup;
import com.opscenter.organization.application.TeamRef;
import com.opscenter.organization.domain.Organization;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.organization.domain.Team;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the organization module's read ports ({@link TeamLookup}, {@link OrganizationLookup})
 * for other modules. Entities never leave this class - only {@link TeamRef} records and ids do.
 */
@Component
public class OrganizationLookupAdapter implements TeamLookup, OrganizationLookup {

    private final TeamRepository teams;
    private final OrganizationRepository organizations;

    public OrganizationLookupAdapter(TeamRepository teams, OrganizationRepository organizations) {
        this.teams = teams;
        this.organizations = organizations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamRef> findTeam(UUID teamId) {
        return teamId == null ? Optional.empty() : teams.findById(teamId).map(OrganizationLookupAdapter::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, TeamRef> findTeams(Collection<UUID> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }
        return teams.findAllById(teamIds.stream().distinct().toList()).stream()
                .map(OrganizationLookupAdapter::toRef)
                .collect(Collectors.toMap(TeamRef::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID defaultOrganizationId() {
        Organization organization = organizations.findByCode(Organization.DEFAULT_CODE)
                .orElseThrow(() -> new NotFoundException(OrganizationErrorCodes.ORGANIZATION_NOT_FOUND,
                        "Default organization is not seeded"));
        if (!organization.isActive()) {
            throw new BusinessRuleException(OrganizationErrorCodes.ORGANIZATION_INACTIVE,
                    "Organization " + organization.getCode() + " is inactive");
        }
        return organization.getId();
    }

    private static TeamRef toRef(Team team) {
        return new TeamRef(team.getId(), team.getOrganizationId(), team.getCode(), team.getName(), team.isActive());
    }
}
