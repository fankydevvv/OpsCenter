package com.opscenter.identity.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.Permission;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.infrastructure.persistence.PermissionRepository;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role administration (04-API §4, 01-SRS FR-IAM-02).
 * <p>
 * {@link #replacePermissions} is the operation TC-RBAC-004 / TC-AUD-002 care about: the audit line
 * carries the complete permission list before and after, so a reviewer can answer "who gave
 * COORDINATOR the right to lock users, and when". Remember D-07: users holding the role see the
 * change on their next refresh or login.
 */
@Service
public class RoleService {

    private static final String RESOURCE_TYPE = "Role";

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuditRecorder audit;

    public RoleService(RoleRepository roles, PermissionRepository permissions, AuditRecorder audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RoleDetail> list() {
        return roles.findAllByOrderByCodeAsc().stream().map(RoleDetail::from).toList();
    }

    @Transactional(readOnly = true)
    public RoleDetail get(UUID id) {
        return RoleDetail.from(load(id));
    }

    @Transactional
    public RoleDetail create(CreateRoleCommand command) {
        Role role = Role.create(command.code(), command.name(), command.description());
        if (roles.existsByCode(role.getCode())) {
            throw new ConflictException(IdentityErrorCodes.ROLE_CODE_TAKEN,
                    "Role code '" + role.getCode() + "' already exists");
        }
        roles.save(role);
        RoleDetail created = RoleDetail.from(role);
        audit.record(AuditAction.ROLE_CREATED, RESOURCE_TYPE, role.getId(), null, created);
        return created;
    }

    @Transactional
    public RoleDetail replacePermissions(UUID id, List<String> permissionCodes) {
        Role role = load(id);
        RoleDetail before = RoleDetail.from(role);
        role.replacePermissions(resolvePermissions(permissionCodes));
        roles.saveAndFlush(role);
        RoleDetail after = RoleDetail.from(role);
        audit.record(AuditAction.ROLE_PERMISSIONS_CHANGED, RESOURCE_TYPE, role.getId(), before, after);
        return after;
    }

    private Role load(UUID id) {
        return roles.findById(id)
                .orElseThrow(() -> new NotFoundException(IdentityErrorCodes.ROLE_NOT_FOUND,
                        "Role " + id + " not found"));
    }

    private Set<Permission> resolvePermissions(List<String> codes) {
        Set<String> wanted = Set.copyOf(codes.stream().map(String::trim).toList());
        List<Permission> found = wanted.isEmpty() ? List.of() : permissions.findByCodeIn(wanted);
        if (found.size() != wanted.size()) {
            List<String> missing = new ArrayList<>(wanted);
            found.forEach(permission -> missing.remove(permission.getCode()));
            throw new NotFoundException(IdentityErrorCodes.PERMISSION_NOT_FOUND,
                    "Unknown permission code(s): " + missing);
        }
        return Set.copyOf(found);
    }
}
