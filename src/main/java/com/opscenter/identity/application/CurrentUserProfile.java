package com.opscenter.identity.application;

import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;

/** Response of {@code GET /auth/me} (blueprint §7.1). */
public record CurrentUserProfile(UUID id, String username, String email, String displayName, UserStatus status,
                                 List<String> roles, List<String> permissions, List<TeamMembershipView> teams) {

    public static CurrentUserProfile from(User user, List<TeamMembershipView> teams) {
        return new CurrentUserProfile(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
                user.getStatus(), user.roleCodes(), user.permissionCodes(), teams);
    }
}
