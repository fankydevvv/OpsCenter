package com.opscenter.identity.application;

import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.User;

/** The {@code user} object of the login/refresh response (04-API §3.1). */
public record AuthenticatedUser(UUID id, String username, String displayName, List<String> roles,
                                List<String> permissions) {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(), user.roleCodes(),
                user.permissionCodes());
    }
}
