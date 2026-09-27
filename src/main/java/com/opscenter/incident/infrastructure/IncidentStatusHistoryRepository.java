package com.opscenter.incident.infrastructure;

import java.util.List;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentStatusHistory;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code incident_status_history} (append-only, 03-DB §13.2). */
public interface IncidentStatusHistoryRepository extends JpaRepository<IncidentStatusHistory, UUID> {

    List<IncidentStatusHistory> findByIncidentIdOrderByChangedAtAsc(UUID incidentId);
}
