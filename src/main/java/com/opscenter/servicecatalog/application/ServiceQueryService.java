package com.opscenter.servicecatalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.EnvironmentCode;
import com.opscenter.servicecatalog.domain.ServiceCatalogErrorCodes;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceSpecifications;
import com.opscenter.shared.application.Sorting;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read use cases of the service catalog ({@code GET /api/v1/services*}, 04-API §5, blueprint §7.1).
 * Sorting is whitelisted ({@link Sorting#restrict}) and DTOs are assembled in batches by
 * {@link ServiceViews}.
 */
@Service
public class ServiceQueryService {

    /** Properties a client may sort by (04-API §2.4); default {@code code}. */
    static final Set<String> SORTABLE = Set.of("code", "name", "status", "updatedAt", "createdAt");

    private final ServiceRepository services;
    private final ServiceEnvironmentRepository environments;
    private final ServiceViews views;

    public ServiceQueryService(ServiceRepository services, ServiceEnvironmentRepository environments,
                               ServiceViews views) {
        this.services = services;
        this.environments = environments;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public Page<ServiceSummary> list(ServiceListQuery query, Pageable pageable) {
        List<Specification<CatalogService>> filters = new ArrayList<>();
        filters.add(ServiceSpecifications.isActive(query.active()));
        if (query.status() != null) {
            filters.add(ServiceSpecifications.hasStatus(query.status()));
        }
        if (query.owningTeamId() != null) {
            filters.add(ServiceSpecifications.ownedByTeam(query.owningTeamId()));
        }
        if (query.q() != null && !query.q().isBlank()) {
            filters.add(ServiceSpecifications.matches(query.q()));
        }
        String environment = EnvironmentCode.normalize(query.environment());
        if (environment != null) {
            filters.add(ServiceSpecifications.hasEnvironment(environment));
        }
        Pageable safe = Sorting.restrict(pageable, SORTABLE, "code");
        Page<CatalogService> page = services.findAll(Specification.allOf(filters), safe);
        return new PageImpl<>(views.summaries(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ServiceDetail get(UUID id) {
        return views.detail(load(id));
    }

    /** @param active {@code null} = all environments, otherwise only active / only inactive ones */
    @Transactional(readOnly = true)
    public List<ServiceEnvironmentView> environments(UUID serviceId, Boolean active) {
        CatalogService service = load(serviceId);
        return environments.findByServiceIdOrderByEnvironmentCodeAsc(service.getId()).stream()
                .filter(e -> active == null || e.isActive() == active)
                .map(views::environment)
                .toList();
    }

    private CatalogService load(UUID id) {
        return services.findById(id)
                .orElseThrow(() -> new NotFoundException(ServiceCatalogErrorCodes.SERVICE_NOT_FOUND,
                        "Service " + id + " not found"));
    }
}
