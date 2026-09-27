package com.opscenter.servicecatalog.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.EnvironmentCode;

/**
 * The service catalog's public API for other modules (blueprint D-37, §8.4). The alert module calls
 * it for every incoming alert; incident and alert lists use {@link #findRefs} to show service names.
 * Nothing outside this module touches the catalog's entities or repositories.
 * <p>
 * Resolution (FR-ALT-05, D-48):
 * <ol>
 *   <li>{@link #keyOf}: take the service code from the first non-blank label of
 *       {@code opscenter.service-catalog.resolution.service-label-keys} ({@code service},
 *       {@code service_name}, {@code app}) and the environment from
 *       {@code environment-label-keys} ({@code environment}, {@code env}); normalise both.</li>
 *   <li>{@link #resolve}: the ACTIVE service with that code in the organization (inactive = UNMAPPED,
 *       D-36), then its ACTIVE environment row. An unknown environment keeps the alert MAPPED.</li>
 * </ol>
 * Results are cached in Redis for 10 minutes and evicted after every catalog change (D-52); a Redis
 * outage only means every call reads PostgreSQL.
 */
public interface ServiceLookup {

    /** Pure function of the labels - no I/O. */
    ServiceKey keyOf(Map<String, String> labels);

    /**
     * @param serviceCode raw or normalised code; {@code null}/invalid gives an UNMAPPED result
     * @param environment raw or canonical environment (aliases accepted); may be {@code null}
     */
    ResolvedService resolve(UUID organizationId, String serviceCode, String environment);

    /** {@link #keyOf} followed by {@link #resolve}. */
    default ResolvedService resolveByLabels(UUID organizationId, Map<String, String> labels) {
        ServiceKey key = keyOf(labels);
        return resolve(organizationId, key.serviceCode(), key.environment());
    }

    /** Services by id in one query (active or not); unknown ids are absent from the map. */
    Map<UUID, ServiceRef> findRefs(Collection<UUID> serviceIds);

    /**
     * The canonical form of an environment code as stored on alerts and incidents ({@code prod},
     * {@code PRD} -> {@code PRODUCTION}, D-33). List filters use it so a user typing an alias finds
     * the same rows the ingestion wrote. {@code null}/blank stays {@code null}.
     */
    static String canonicalEnvironment(String raw) {
        return EnvironmentCode.normalize(raw);
    }
}
