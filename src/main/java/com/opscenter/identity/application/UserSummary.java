package com.opscenter.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;

/** One row of {@code GET /api/v1/users} (blueprint §7.2). Never contains the password hash. */
public record UserSummary(UUID id, String username, String email, String displayName, UserStatus status,
                          List<String> roles, Instant createdAt) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getStatus(), user.roleCodes(), user.getCreatedAt());
    }
}
