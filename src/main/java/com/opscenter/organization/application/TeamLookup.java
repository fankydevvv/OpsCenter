package com.opscenter.organization.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port through which other modules (service catalog, incident ...) look teams up
 * (blueprint D-37, 02-SAD §2.1 "modules talk through application interfaces").
 * <p>
 * The batch variant exists for list endpoints: one query for all teams of a page instead of one
 * per row (the N+1 problem).
 */
public interface TeamLookup {

    Optional<TeamRef> findTeam(UUID teamId);

    /** Teams by id; unknown ids are simply absent from the map. */
    Map<UUID, TeamRef> findTeams(Collection<UUID> teamIds);
}
