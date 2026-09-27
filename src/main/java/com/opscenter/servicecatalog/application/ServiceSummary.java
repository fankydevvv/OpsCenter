package com.opscenter.servicecatalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/** One row of {@code GET /api/v1/services} (blueprint §7.1). */
public record ServiceSummary(UUID id, String code, String name, ServiceStatus status, boolean active,
                             TeamBrief owningTeam, UserBrief primaryOwner, UserBrief backupOwner,
                             List<ServiceEnvironmentBrief> environments, Instant updatedAt, long version) {
}
