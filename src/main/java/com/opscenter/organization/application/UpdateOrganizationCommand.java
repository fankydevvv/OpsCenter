package com.opscenter.organization.application;

import com.opscenter.organization.domain.MasterDataStatus;

/** Input of {@code OrganizationService.update}; {@code null} fields are left unchanged. */
public record UpdateOrganizationCommand(String name, MasterDataStatus status, long version) {
}
