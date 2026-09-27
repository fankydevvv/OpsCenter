package com.opscenter.alert.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.alert.domain.Alert;
import com.opscenter.alert.domain.AlertStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data access to {@code alerts}. The ingestion queries are all "by fingerprint" and hit
 * {@code idx_alerts_fingerprint_status} / {@code uk_alerts_firing_fingerprint}.
 */
public interface AlertRepository extends JpaRepository<Alert, UUID>, JpaSpecificationExecutor<Alert> {

    /** The current FIRING episode of a fingerprint - at most one (partial unique index, D-47). */
    Optional<Alert> findByOrganizationIdAndFingerprintAndStatus(UUID organizationId, String fingerprint,
                                                                AlertStatus status);

    /** Most recent episode of a fingerprint, whatever its status. */
    Optional<Alert> findFirstByOrganizationIdAndFingerprintOrderByLastSeenAtDesc(UUID organizationId,
                                                                                 String fingerprint);

    /** "Has this fingerprint ever been seen?" - distinguishes REFIRED from CREATED. */
    boolean existsByOrganizationIdAndFingerprint(UUID organizationId, String fingerprint);

    long countByStatus(AlertStatus status);

    long countByStatusAndServiceIdIsNull(AlertStatus status);

    long countByFirstSeenAtGreaterThanEqual(Instant since);
}
