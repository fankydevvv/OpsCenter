package com.opscenter.identity.application;

import java.util.UUID;

import com.opscenter.identity.domain.Permission;

/** One row of {@code GET /api/v1/permissions} (04-API §4). */
public record PermissionDetail(UUID id, String code, String resource, String action) {

    public static PermissionDetail from(Permission permission) {
        return new PermissionDetail(permission.getId(), permission.getCode(), permission.getResource(),
                permission.getAction());
    }
}
