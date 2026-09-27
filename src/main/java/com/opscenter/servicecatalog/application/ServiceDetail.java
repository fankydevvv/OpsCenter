package com.opscenter.servicecatalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

import tools.jackson.databind.JsonNode;

/**
 * Full representation of a service with its environments and owners (blueprint §7.1). It is also
 * the before/after snapshot of every {@code SERVICE_*} audit line, so it deliberately contains no
 * credential (metadata with credential-like keys is rejected on input).
 */
public record ServiceDetail(UUID id, UUID organizationId, String code, String name, String description,
                            ServiceStatus status, boolean active, TeamBrief owningTeam, UserBrief primaryOwner,
                            UserBrief backupOwner, JsonNode metadata, List<ServiceEnvironmentView> environments,
                            List<ServiceOwnerView> owners, Instant createdAt, Instant updatedAt, Instant deletedAt,
                            long version) {
}
