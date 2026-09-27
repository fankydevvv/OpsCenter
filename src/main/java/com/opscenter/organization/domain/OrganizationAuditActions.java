package com.opscenter.organization.domain;

/**
 * Audit actions emitted only by this module; the base catalogue in
 * {@code com.opscenter.audit.domain.AuditAction} already holds the {@code TEAM_*} ones (D-15).
 */
public final class OrganizationAuditActions {

    public static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";

    private OrganizationAuditActions() {
    }
}
