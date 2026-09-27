package com.opscenter.identity.application;

import java.util.List;
import java.util.UUID;

/**
 * Port that tells identity which teams a user belongs to.
 * <p>
 * Teams live in the {@code organization} module, and that module already depends on identity
 * (a team member <em>is</em> a user). If identity imported organization back, the two modules
 * would form a cycle and could never be separated. Dependency inversion breaks the cycle: identity
 * declares what it needs, organization provides the implementation
 * ({@code TeamMembershipQueryAdapter}), and Spring wires them at runtime.
 */
public interface TeamMembershipQuery {

    /** Active-team memberships of the user, sorted by team code; empty when the user has none. */
    List<TeamMembershipView> membershipsOf(UUID userId);
}
