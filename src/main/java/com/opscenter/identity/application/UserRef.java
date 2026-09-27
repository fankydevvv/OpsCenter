package com.opscenter.identity.application;

import java.util.UUID;

/**
 * The facts other modules may know about a user (blueprint D-37): identity for display and
 * whether the account can still act as an owner/assignee. Never a hash, e-mail or role.
 *
 * @param active  status {@code ACTIVE} and not soft-deleted
 * @param deleted soft-deleted ({@code DISABLED} + {@code deleted_at}, D-26)
 */
public record UserRef(UUID id, String username, String displayName, boolean active, boolean deleted) {
}
