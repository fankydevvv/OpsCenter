package com.opscenter.servicecatalog.application;

import java.util.UUID;

/**
 * Result of resolving an alert's labels to the catalog (FR-ALT-05, blueprint §8.4, D-48).
 *
 * @param serviceCode          normalised code from the labels ({@code null} when absent) - kept on
 *                             the alert even when unmapped, so an administrator can see what to register
 * @param environment          canonical environment from the labels ({@code null} when absent)
 * @param serviceId            the ACTIVE service with that code in the organization, or {@code null} = UNMAPPED
 * @param serviceName          its name (display only)
 * @param owningTeamId         its owning team (routing, Sprint 3), may be {@code null}
 * @param serviceEnvironmentId the ACTIVE environment row, or {@code null} when that environment is not
 *                             registered for the service - the alert is still MAPPED (timeline note)
 */
public record ResolvedService(String serviceCode, String environment, UUID serviceId, String serviceName,
                              UUID owningTeamId, UUID serviceEnvironmentId) {

    public static ResolvedService unmapped(String serviceCode, String environment) {
        return new ResolvedService(serviceCode, environment, null, null, null, null);
    }

    public boolean mapped() {
        return serviceId != null;
    }

    public MappingStatus mappingStatus() {
        return mapped() ? MappingStatus.MAPPED : MappingStatus.UNMAPPED;
    }

    /** {@code true} when the environment row exists and is active. */
    public boolean environmentRegistered() {
        return serviceEnvironmentId != null;
    }
}
