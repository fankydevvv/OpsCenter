package com.opscenter.identity.domain;

/**
 * Audit actions that only the identity module emits and that extend the base catalogue in
 * {@code com.opscenter.audit.domain.AuditAction}. The audit module keeps its catalogue as plain
 * constants precisely so a business module can add its own without editing it (D-15); the
 * blueprint lists these additions under D-26.
 */
public final class IdentityAuditActions {

    /** Soft delete of an account ({@code DELETE /api/v1/users/{id}}, D-26). */
    public static final String USER_DELETED = "USER_DELETED";

    private IdentityAuditActions() {
    }
}
