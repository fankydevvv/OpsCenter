package com.opscenter.audit.domain;

/**
 * Catalogue of audit action names (D-15). Constants instead of an enum so a business module can
 * add its own actions in a later sprint without editing the audit module; the value is stored as
 * {@code audit_logs.action}.
 */
public final class AuditAction {

    public static final String AUTH_LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String AUTH_LOGIN_FAILED = "AUTH_LOGIN_FAILED";
    public static final String AUTH_LOGOUT = "AUTH_LOGOUT";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_LOCKED = "USER_LOCKED";
    public static final String USER_UNLOCKED = "USER_UNLOCKED";
    public static final String USER_ROLES_CHANGED = "USER_ROLES_CHANGED";

    public static final String ROLE_CREATED = "ROLE_CREATED";
    public static final String ROLE_PERMISSIONS_CHANGED = "ROLE_PERMISSIONS_CHANGED";

    public static final String TEAM_CREATED = "TEAM_CREATED";
    public static final String TEAM_UPDATED = "TEAM_UPDATED";
    public static final String TEAM_MEMBER_ADDED = "TEAM_MEMBER_ADDED";
    public static final String TEAM_MEMBER_REMOVED = "TEAM_MEMBER_REMOVED";

    private AuditAction() {
    }
}
