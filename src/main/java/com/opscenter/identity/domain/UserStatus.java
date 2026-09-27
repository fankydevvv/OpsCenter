package com.opscenter.identity.domain;

/**
 * Account lifecycle (03-DB §5.1, CHECK constraint in V001).
 * <ul>
 *   <li>{@code ACTIVE} - may log in.</li>
 *   <li>{@code LOCKED} - locked by an administrator; every session was revoked; can be unlocked.</li>
 *   <li>{@code DISABLED} - soft-deleted account ({@code deleted_at} set); not meant to come back.</li>
 * </ul>
 */
public enum UserStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
