package com.opscenter.organization.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.organization.domain.Organization;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code organizations}; a handful of rows at most, so no pagination. */
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findByCode(String code);

    List<Organization> findAllByOrderByCodeAsc();
}
