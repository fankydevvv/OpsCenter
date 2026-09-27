package com.opscenter.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.opscenter.identity.domain.Permission;

import org.springframework.data.jpa.repository.JpaRepository;

/** Read access to the permission catalogue ({@code permissions}); rows come from seed migrations only. */
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    List<Permission> findByCodeIn(Collection<String> codes);

    List<Permission> findAllByOrderByCodeAsc();
}
