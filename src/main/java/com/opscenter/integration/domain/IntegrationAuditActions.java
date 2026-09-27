package com.opscenter.integration.domain;

/**
 * Audit actions of the integration module (blueprint §9.2). Failed authentications are deliberately
 * NOT audited: an attacker could otherwise flood {@code audit_logs}; they are logged (without the
 * token) and counted in {@code opscenter.webhook.requests{result=auth_failed}} instead.
 */
public final class IntegrationAuditActions {

    /** One accepted delivery, with its counters (resource {@code IntegrationSource}). */
    public static final String INTEGRATION_EVENT_RECEIVED = "INTEGRATION_EVENT_RECEIVED";

    private IntegrationAuditActions() {
    }
}
