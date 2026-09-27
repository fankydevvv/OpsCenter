package com.opscenter.identity.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.identity.application.RoleDetail;
import com.opscenter.identity.application.RoleService;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/roles} (04-API §4): a small, unpaginated list; permissions replaced as a whole. */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('role.read')")
    public List<RoleDetail> list() {
        return roles.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role.read')")
    public RoleDetail get(@PathVariable UUID id) {
        return roles.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role.create')")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleDetail create(@Valid @RequestBody CreateRoleRequest request) {
        return roles.create(request.toCommand());
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role.permission.update')")
    public RoleDetail replacePermissions(@PathVariable UUID id, @Valid @RequestBody ReplacePermissionsRequest request) {
        return roles.replacePermissions(id, request.permissionCodes());
    }
}
