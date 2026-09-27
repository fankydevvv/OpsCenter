package com.opscenter.servicecatalog.api;

import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.servicecatalog.application.ServiceCatalogService;
import com.opscenter.servicecatalog.application.ServiceEnvironmentView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/service-environments/{id}} (04-API §5): an environment has its own id and version,
 * so it is edited directly rather than through its service.
 */
@RestController
@RequestMapping("/api/v1/service-environments")
public class ServiceEnvironmentController {

    private final ServiceCatalogService catalog;

    public ServiceEnvironmentController(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('service.update')")
    public ServiceEnvironmentView update(@PathVariable UUID id, @Valid @RequestBody UpdateEnvironmentRequest request) {
        return catalog.updateEnvironment(id, request.toCommand());
    }
}
