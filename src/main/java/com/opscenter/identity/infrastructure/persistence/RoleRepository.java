package com.opscenter.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.identity.domain.Role;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code roles}; the list is small, so no pagination (04-API §4). */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    List<Role> findByCodeIn(Collection<String> codes);

    List<Role> findAllByOrderByCodeAsc();
}
