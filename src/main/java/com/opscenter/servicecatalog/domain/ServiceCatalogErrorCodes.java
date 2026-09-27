package com.opscenter.servicecatalog.domain;

/**
 * Error codes of the service catalog (04-API §17 naming {@code DOMAIN_REASON}; blueprint §7.7).
 * Teams and users that do not exist reuse {@code TEAM_NOT_FOUND} / {@code USER_NOT_FOUND} of their
 * own modules so a client sees one spelling per concept.
 */
public final class ServiceCatalogErrorCodes {

    /** 404 */
    public static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";
    /** 404 */
    public static final String SERVICE_ENVIRONMENT_NOT_FOUND = "SERVICE_ENVIRONMENT_NOT_FOUND";
    /** 409 - {@code services.code} is unique per organization. */
    public static final String SERVICE_CODE_TAKEN = "SERVICE_CODE_TAKEN";
    /** 409 - one row per (service, environment code) - TC-SVC-003. */
    public static final String SERVICE_ENVIRONMENT_EXISTS = "SERVICE_ENVIRONMENT_EXISTS";
    /** 422 - an owner (team or user) exists but is inactive/locked. */
    public static final String SERVICE_OWNER_INACTIVE = "SERVICE_OWNER_INACTIVE";
    /** 400 - primary and backup owner must be different people. */
    public static final String SERVICE_OWNERS_MUST_DIFFER = "SERVICE_OWNERS_MUST_DIFFER";
    /** 400 - an additional owner must name exactly one of team / user (or a team of another organization). */
    public static final String SERVICE_OWNER_TARGET_INVALID = "SERVICE_OWNER_TARGET_INVALID";
    /** 400 - the same (target, ownership type) listed twice. */
    public static final String SERVICE_OWNER_DUPLICATE = "SERVICE_OWNER_DUPLICATE";

    private ServiceCatalogErrorCodes() {
    }
}
