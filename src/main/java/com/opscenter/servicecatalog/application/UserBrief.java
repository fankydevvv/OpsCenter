package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.identity.application.UserRef;

/** A user as shown inside service DTOs: {@code {id, username, displayName}} (blueprint §7.1). */
public record UserBrief(UUID id, String username, String displayName) {

    public static UserBrief from(UserRef user) {
        return user == null ? null : new UserBrief(user.id(), user.username(), user.displayName());
    }
}
