package com.opscenter.organization.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.organization.application.OrganizationService;
import com.opscenter.organization.application.OrganizationView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/organizations} (02-SAD §6.2, 03-DB §6.1): read and rename; no create in the base. */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('organization.read')")
    public List<OrganizationView> list() {
        return organizations.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('organization.read')")
    public OrganizationView get(@PathVariable UUID id) {
        return organizations.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('organization.update')")
    public OrganizationView update(@PathVariable UUID id, @Valid @RequestBody UpdateOrganizationRequest request) {
        return organizations.update(id, request.toCommand());
    }
}
