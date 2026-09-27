package com.opscenter.identity.application;

import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.Role;

/**
 * Representation of a role with its permission codes (blueprint §7.2). Used as the before/after
 * snapshot of {@code ROLE_PERMISSIONS_CHANGED} so an auditor sees exactly which codes moved
 * (TC-RBAC-004, TC-AUD-002).
 */
public record RoleDetail(UUID id, String code, String name, String description, List<String> permissions,
                         long version) {

    public static RoleDetail from(Role role) {
        return new RoleDetail(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                role.permissionCodes(), role.getVersion());
    }
}
