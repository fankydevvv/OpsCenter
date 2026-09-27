package com.opscenter.organization.application;

import com.opscenter.organization.domain.MasterDataStatus;

/**
 * Input of {@code TeamService.update} ({@code PATCH /teams/{id}}); {@code null} fields are left
 * unchanged, {@code status = INACTIVE} is the soft delete, {@code version} is the lock token.
 */
public record UpdateTeamCommand(String name, String description, String teamType, Boolean onCallEnabled,
                                MasterDataStatus status, long version) {
}
