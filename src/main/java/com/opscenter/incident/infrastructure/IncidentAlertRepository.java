package com.opscenter.incident.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentAlert;
import com.opscenter.incident.domain.IncidentAlertId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code incident_alerts} (03-DB §38.3). */
public interface IncidentAlertRepository extends JpaRepository<IncidentAlert, IncidentAlertId> {

    List<IncidentAlert> findByIdIncidentIdOrderByLinkedAtAsc(UUID incidentId);

    List<IncidentAlert> findByIdAlertIdIn(Collection<UUID> alertIds);

    @Query("select ia.id.incidentId from IncidentAlert ia where ia.id.alertId = :alertId")
    List<UUID> findIncidentIdsByAlertId(@Param("alertId") UUID alertId);

    @Query("select ia.id.alertId from IncidentAlert ia where ia.id.incidentId = :incidentId")
    List<UUID> findAlertIdsByIncidentId(@Param("incidentId") UUID incidentId);

    /** Rows {@code [incidentId, count]} - the {@code alertCount} of an incident list page in one query. */
    @Query("select ia.id.incidentId, count(ia) from IncidentAlert ia where ia.id.incidentId in :incidentIds "
            + "group by ia.id.incidentId")
    List<Object[]> countByIncidentIds(@Param("incidentIds") Collection<UUID> incidentIds);
}
