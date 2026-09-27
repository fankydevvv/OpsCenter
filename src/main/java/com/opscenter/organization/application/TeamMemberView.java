package com.opscenter.organization.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.organization.domain.MemberType;

/**
 * A member inside {@link TeamDetail}. Username and display name are copied from identity at read
 * time (they are not stored on {@code team_members}), so the UI can render a member list without
 * a second round trip.
 */
public record TeamMemberView(UUID userId, String username, String displayName, MemberType memberType,
                             boolean isPrimary, String teamRole, Instant joinedAt) {
}
