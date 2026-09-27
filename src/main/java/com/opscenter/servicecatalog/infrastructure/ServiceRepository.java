package com.opscenter.servicecatalog.infrastructure;

import java.util.Optional;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.CatalogService;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data access to {@code services}. The list filters are composed with
 * {@link ServiceSpecifications} (optional parameters are added only when present).
 */
public interface ServiceRepository extends JpaRepository<CatalogService, UUID>, JpaSpecificationExecutor<CatalogService> {

    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    /** The resolver's query (FR-ALT-05): only an ACTIVE row of the organization can own an alert (D-36). */
    Optional<CatalogService> findByOrganizationIdAndCodeAndActiveTrue(UUID organizationId, String code);
}
