package com.opscenter.identity.api;

import java.util.List;

import com.opscenter.identity.application.PermissionDetail;
import com.opscenter.identity.application.PermissionService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/permissions} (04-API §4): the catalogue the role editor is built from. */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final PermissionService permissions;

    public PermissionController(PermissionService permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('permission.read')")
    public List<PermissionDetail> list() {
        return permissions.list();
    }
}
