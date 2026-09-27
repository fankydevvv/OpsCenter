package com.opscenter.organization.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.Organization;

/** Representation of an organization (blueprint §7.3) and audit snapshot for {@code ORGANIZATION_UPDATED}. */
public record OrganizationView(UUID id, String code, String name, MasterDataStatus status, Instant createdAt,
                               Instant updatedAt, long version) {

    public static OrganizationView from(Organization organization) {
        return new OrganizationView(organization.getId(), organization.getCode(), organization.getName(),
                organization.getStatus(), organization.getCreatedAt(), organization.getUpdatedAt(),
                organization.getVersion());
    }
}
