package com.opscenter.servicecatalog.domain;

/**
 * Operational status of a service or of one of its environments (03-DB §7.1/§38.2 CHECK
 * constraint). This is <em>declared</em> state maintained by people ("we are in a maintenance
 * window"), not live health - live health comes from Prometheus alerts. Whether a row is
 * soft-deleted is a separate flag ({@code is_active}), so {@code MAINTENANCE} never hides a service.
 */
public enum ServiceStatus {
    ACTIVE,
    DEGRADED,
    MAINTENANCE
}
