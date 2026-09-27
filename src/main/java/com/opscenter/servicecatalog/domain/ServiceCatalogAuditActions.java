package com.opscenter.servicecatalog.domain;

/**
 * Audit actions of the service catalog (blueprint §9.2, 01-SRS §15). Master data changes are
 * audited with before/after snapshots but publish no outbox event (blueprint §9.1).
 */
public final class ServiceCatalogAuditActions {

    public static final String SERVICE_CREATED = "SERVICE_CREATED";
    public static final String SERVICE_UPDATED = "SERVICE_UPDATED";
    public static final String SERVICE_DEACTIVATED = "SERVICE_DEACTIVATED";
    public static final String SERVICE_OWNERS_CHANGED = "SERVICE_OWNERS_CHANGED";
    public static final String SERVICE_ENVIRONMENT_CREATED = "SERVICE_ENVIRONMENT_CREATED";
    public static final String SERVICE_ENVIRONMENT_UPDATED = "SERVICE_ENVIRONMENT_UPDATED";

    private ServiceCatalogAuditActions() {
    }
}
