package com.opscenter.servicecatalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.EnvironmentCode;
import com.opscenter.servicecatalog.domain.ServiceCode;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the catalog's public {@link ServiceLookup} API (FR-ALT-05, blueprint §8.4,
 * D-44, D-48, D-52).
 * <p>
 * Read path of {@link #resolve}: normalise -> Redis ({@link ServiceResolutionCache}) -> on a miss
 * PostgreSQL -> store the answer (also a negative "NONE") in Redis. The cache is only an
 * accelerator: PostgreSQL stays the source of truth (05-DEPLOY §23.1) and every catalog change
 * deletes the cached key after commit ({@link ServiceCatalogService}).
 */
@Service
public class ServiceLookupService implements ServiceLookup {

    private final ServiceRepository services;
    private final ServiceEnvironmentRepository environments;
    private final ServiceResolutionCache cache;
    private final ServiceCatalogProperties.Resolution resolution;

    public ServiceLookupService(ServiceRepository services, ServiceEnvironmentRepository environments,
                                ServiceResolutionCache cache, ServiceCatalogProperties properties) {
        this.services = services;
        this.environments = environments;
        this.cache = cache;
        this.resolution = properties.resolution();
    }

    @Override
    public ServiceKey keyOf(Map<String, String> labels) {
        return new ServiceKey(
                ServiceCode.normalize(firstNonBlank(labels, resolution.serviceLabelKeys())),
                EnvironmentCode.normalize(firstNonBlank(labels, resolution.environmentLabelKeys())));
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedService resolve(UUID organizationId, String serviceCode, String environment) {
        String code = ServiceCode.normalize(serviceCode);
        String env = EnvironmentCode.normalize(environment);
        if (organizationId == null || !ServiceCode.isValid(code)) {
            // Not a catalog code at all (e.g. "Payment API"): nothing to look up, nothing to cache.
            return ResolvedService.unmapped(code, env);
        }
        Optional<ServiceResolutionCache.Entry> cached = cache.get(organizationId, code, env);
        ServiceResolutionCache.Entry entry = cached.orElseGet(() -> {
            ServiceResolutionCache.Entry loaded = load(organizationId, code, env);
            cache.put(organizationId, code, env, loaded);
            return loaded;
        });
        return entry.mapped()
                ? new ResolvedService(code, env, entry.serviceId(), entry.serviceName(), entry.owningTeamId(),
                        entry.serviceEnvironmentId())
                : ResolvedService.unmapped(code, env);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ServiceRef> findRefs(Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Map.of();
        }
        return services.findAllById(serviceIds.stream().distinct().toList()).stream()
                .map(s -> new ServiceRef(s.getId(), s.getOrganizationId(), s.getCode(), s.getName(), s.getStatus(),
                        s.isActive(), s.getOwningTeamId()))
                .collect(Collectors.toMap(ServiceRef::id, Function.identity()));
    }

    /** D-48: ACTIVE service of the organization, then its ACTIVE environment (absent = not registered). */
    private ServiceResolutionCache.Entry load(UUID organizationId, String code, String env) {
        Optional<CatalogService> service = services.findByOrganizationIdAndCodeAndActiveTrue(organizationId, code);
        if (service.isEmpty()) {
            return ServiceResolutionCache.Entry.NONE;
        }
        CatalogService found = service.get();
        UUID environmentId = env == null || !EnvironmentCode.isValid(env) ? null
                : environments.findByServiceIdAndEnvironmentCodeAndActiveTrue(found.getId(), env)
                        .map(ServiceEnvironment::getId).orElse(null);
        return new ServiceResolutionCache.Entry(found.getId(), environmentId, found.getOwningTeamId(), found.getName());
    }

    private static String firstNonBlank(Map<String, String> labels, List<String> keys) {
        if (labels == null) {
            return null;
        }
        for (String key : keys) {
            String value = labels.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
