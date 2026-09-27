package com.opscenter.organization.application;

import java.util.UUID;

/**
 * Read-only port for modules that must stamp an {@code organization_id} on their rows (blueprint
 * D-37). The thesis runs a single organization, {@code DEFAULT} (V005); resolving it through this
 * port keeps a later multi-tenant change inside the organization module.
 */
public interface OrganizationLookup {

    /**
     * @return id of the active {@code DEFAULT} organization
     * @throws com.opscenter.shared.domain.NotFoundException     {@code ORGANIZATION_NOT_FOUND} when not seeded
     * @throws com.opscenter.shared.domain.BusinessRuleException {@code ORGANIZATION_INACTIVE} when soft-deleted
     */
    UUID defaultOrganizationId();
}
