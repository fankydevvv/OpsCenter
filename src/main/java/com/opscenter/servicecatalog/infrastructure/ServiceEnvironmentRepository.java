package com.opscenter.servicecatalog.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceEnvironment;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code service_environments}. */
public interface ServiceEnvironmentRepository extends JpaRepository<ServiceEnvironment, UUID> {

    List<ServiceEnvironment> findByServiceIdOrderByEnvironmentCodeAsc(UUID serviceId);

    /** All environments of a page of services in ONE query (avoids N+1 in the list endpoint). */
    List<ServiceEnvironment> findByServiceIdInOrderByEnvironmentCodeAsc(Collection<UUID> serviceIds);

    boolean existsByServiceIdAndEnvironmentCode(UUID serviceId, String environmentCode);

    Optional<ServiceEnvironment> findByServiceIdAndEnvironmentCodeAndActiveTrue(UUID serviceId, String environmentCode);
}
