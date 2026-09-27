package com.opscenter.incident.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.incident.domain.Incident;
import com.opscenter.incident.domain.IncidentStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code incidents}. List filters are composed with
 * {@link IncidentSpecifications}; the queries below serve correlation (D-54) and the Operations
 * Center summary.
 */
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    /**
     * Next number for {@code INC-000001} (D-60). A PostgreSQL sequence never hands the same value to
     * two transactions, so two incidents created at the same moment cannot collide - unlike
     * {@code select max(...) + 1}.
     */
    @Query(value = "select nextval('incident_no_seq')", nativeQuery = true)
    long nextIncidentNumber();

    /** The open incident of a correlation group - at most one thanks to {@code uk_incidents_open_correlation}. */
    Optional<Incident> findFirstByOrganizationIdAndFingerprintAndStatusIn(UUID organizationId, String fingerprint,
                                                                          Collection<IncidentStatus> statuses);

    /** Most recently resolved incident of a group, resolved at or after {@code since} (reopen window, D-54). */
    Optional<Incident> findFirstByOrganizationIdAndFingerprintAndStatusAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(
            UUID organizationId, String fingerprint, IncidentStatus status, Instant since);

    /**
     * Adds {@code delta} to the occurrence counter of the given incidents <b>without</b> touching
     * {@code version} (D-57): counters are commutative and must never make a human command fail
     * with 409. Native SQL because the entity maps the column read-only. Pending entity changes are
     * flushed first so the statement sees them.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "update incidents set occurrence_count = occurrence_count + :delta, updated_at = :now "
            + "where id in (:ids)", nativeQuery = true)
    int incrementOccurrences(@Param("ids") Collection<UUID> ids, @Param("delta") long delta, @Param("now") Instant now);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    long countByStatusInAndServiceIdIsNull(Collection<IncidentStatus> statuses);

    /** Rows {@code [Severity, Long]} of open incidents grouped by severity. */
    @Query("select i.severity, count(i) from Incident i where i.status in :statuses group by i.severity")
    List<Object[]> countBySeverity(@Param("statuses") Collection<IncidentStatus> statuses);

    /** Rows {@code [IncidentStatus, Long]} of open incidents grouped by status. */
    @Query("select i.status, count(i) from Incident i where i.status in :statuses group by i.status")
    List<Object[]> countByStatus(@Param("statuses") Collection<IncidentStatus> statuses);

    List<Incident> findTop10ByStatusInOrderByCreatedAtDesc(Collection<IncidentStatus> statuses);
}
