package com.opscenter.servicecatalog.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.servicecatalog.application.ServiceCatalogService;
import com.opscenter.servicecatalog.application.ServiceDetail;
import com.opscenter.servicecatalog.application.ServiceEnvironmentView;
import com.opscenter.servicecatalog.application.ServiceListQuery;
import com.opscenter.servicecatalog.application.ServiceQueryService;
import com.opscenter.servicecatalog.application.ServiceSummary;
import com.opscenter.servicecatalog.domain.ServiceStatus;
import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.application.IdempotentResult;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/services} (04-API §5, blueprint §7.1). Permissions: {@code service.read} for
 * reads, {@code service.create} for {@code POST}, {@code service.update} for every other change.
 * There is intentionally no {@code DELETE}: {@code PATCH {active:false}} is the soft delete (D-36).
 * The controller only binds and delegates - no business rule lives here.
 */
@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    /** Optional header that makes {@code POST} safe to retry (04-API §2.5, D-13). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ServiceCatalogService catalog;
    private final ServiceQueryService queries;

    public ServiceController(ServiceCatalogService catalog, ServiceQueryService queries) {
        this.catalog = catalog;
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('service.read')")
    public PageResponse<ServiceSummary> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) ServiceStatus status,
                                             @RequestParam(required = false) UUID owningTeamId,
                                             @RequestParam(required = false) String environment,
                                             @RequestParam(defaultValue = "true") boolean active,
                                             Pageable pageable) {
        return PageResponse.from(queries.list(new ServiceListQuery(q, status, owningTeamId, environment, active),
                pageable));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('service.create')")
    public ResponseEntity<ServiceDetail> create(@Valid @RequestBody CreateServiceRequest request,
                                                @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                                String idempotencyKey) {
        IdempotentResult<ServiceDetail> result = catalog.create(request.toCommand(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.value());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('service.read')")
    public ServiceDetail get(@PathVariable UUID id) {
        return queries.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('service.update')")
    public ServiceDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateServiceRequest request) {
        return catalog.update(id, request.toCommand());
    }

    @PutMapping("/{id}/ownership")
    @PreAuthorize("hasAuthority('service.update')")
    public ServiceDetail replaceOwnership(@PathVariable UUID id, @Valid @RequestBody ReplaceOwnershipRequest request) {
        return catalog.replaceOwnership(id, request.toCommand());
    }

    @GetMapping("/{id}/environments")
    @PreAuthorize("hasAuthority('service.read')")
    public List<ServiceEnvironmentView> environments(@PathVariable UUID id,
                                                     @RequestParam(required = false) Boolean active) {
        return queries.environments(id, active);
    }

    @PostMapping("/{id}/environments")
    @PreAuthorize("hasAuthority('service.update')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceEnvironmentView addEnvironment(@PathVariable UUID id, @Valid @RequestBody EnvironmentRequest request) {
        return catalog.addEnvironment(id, request.toSpec());
    }
}
