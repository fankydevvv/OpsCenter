package com.opscenter.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;

/**
 * Full representation of a user (blueprint §7.2). It doubles as the audit snapshot for
 * {@code USER_*} actions, which is why it must never carry the password hash (TC-AUD-004);
 * {@code version} lets the client send an optimistic-lock token back with {@code PATCH}.
 */
public record UserDetail(UUID id, String username, String email, String displayName, UserStatus status,
                         List<String> roles, Instant lastLoginAt, Instant createdAt, Instant updatedAt, long version,
                         List<TeamMembershipView> teams) {

    public static UserDetail from(User user, List<TeamMembershipView> teams) {
        return new UserDetail(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getStatus(), user.roleCodes(), user.getLastLoginAt(), user.getCreatedAt(), user.getUpdatedAt(),
                user.getVersion(), teams);
    }
}
