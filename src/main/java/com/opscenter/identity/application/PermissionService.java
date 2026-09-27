package com.opscenter.identity.application;

import java.util.List;

import com.opscenter.identity.infrastructure.persistence.PermissionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only catalogue of permission codes ({@code GET /api/v1/permissions}). The admin UI uses it
 * to render the role/permission matrix; nothing creates permissions at runtime (see
 * {@code Permission}).
 */
@Service
public class PermissionService {

    private final PermissionRepository permissions;

    public PermissionService(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<PermissionDetail> list() {
        return permissions.findAllByOrderByCodeAsc().stream().map(PermissionDetail::from).toList();
    }
}
