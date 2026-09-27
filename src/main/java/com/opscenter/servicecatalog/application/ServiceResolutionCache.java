package com.opscenter.servicecatalog.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Port of the resolution cache (blueprint D-52). The adapter keeps one Redis hash per
 * (organization, service code) with one field per environment, so changing anything about a
 * service is a single {@code DEL}. Implementations never throw: a cache failure is a miss.
 */
public interface ServiceResolutionCache {

    /**
     * Cached resolution of (organization, code, environment) - {@link Entry#NONE} is a cached
     * "no such service" (negative caching: an unknown service alerting every minute should not hit
     * PostgreSQL every minute).
     */
    Optional<Entry> get(UUID organizationId, String serviceCode, String environment);

    void put(UUID organizationId, String serviceCode, String environment, Entry entry);

    /** Forgets every environment of (organization, code); called after commit of a catalog change. */
    void evict(UUID organizationId, String serviceCode);

    /**
     * Cached value; all ids {@code null} = unmapped.
     */
    record Entry(UUID serviceId, UUID serviceEnvironmentId, UUID owningTeamId, String serviceName) {

        public static final Entry NONE = new Entry(null, null, null, null);

        public boolean mapped() {
            return serviceId != null;
        }
    }
}
