package com.opscenter.identity.application;

import java.util.UUID;

/**
 * A user's membership in one team as shown by {@code GET /auth/me} and {@code UserDetail}
 * (blueprint §7.1: {@code teams: [{id, code, name, memberType}]}).
 */
public record TeamMembershipView(UUID id, String code, String name, String memberType) {
}
