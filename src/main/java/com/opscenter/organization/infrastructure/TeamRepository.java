package com.opscenter.organization.infrastructure;

import java.util.UUID;

import com.opscenter.organization.domain.Team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data access to {@code teams}. Search filters are composed with {@link TeamSpecifications}
 * for the same reason as in identity: optional parameters are added to the query only when given.
 */
public interface TeamRepository extends JpaRepository<Team, UUID>, JpaSpecificationExecutor<Team> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);
}
