package com.opscenter.identity.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port through which other modules resolve user ids to {@link UserRef}s (blueprint D-37)
 * - service owners, incident assignees - without touching the {@code User} entity or its repository.
 */
public interface UserLookup {

    Optional<UserRef> findUser(UUID userId);

    /** Users by id in one query; unknown ids are absent from the map. */
    Map<UUID, UserRef> findUsers(Collection<UUID> userIds);
}
