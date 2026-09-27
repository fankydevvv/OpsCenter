package com.opscenter.shared.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Port that tells application services <em>who</em> is acting, without exposing Spring Security.
 * <p>
 * The implementation ({@code SecurityCurrentUser}) reads the JWT claims; services and the JPA
 * auditor only see this interface, which keeps them unit-testable with a stub. All values are
 * empty for unauthenticated requests (login) and scheduled jobs.
 */
public interface CurrentUser {

    /** The {@code sub} claim: id of the authenticated user. */
    Optional<UUID> actorId();

    /** The {@code sid} claim: id of the login session that issued the token (D-03). */
    Optional<UUID> sessionId();

    /** The {@code username} claim, for log lines and messages. */
    Optional<String> username();
}
