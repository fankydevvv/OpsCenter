package com.opscenter.incident.infrastructure;

import java.util.List;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentTimelineEntry;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code incident_timeline} (append-only, 03-DB §13.3). */
public interface IncidentTimelineRepository extends JpaRepository<IncidentTimelineEntry, UUID> {

    Page<IncidentTimelineEntry> findByIncidentId(UUID incidentId, Pageable pageable);

    List<IncidentTimelineEntry> findByIncidentIdOrderByEventAtAsc(UUID incidentId);
}
