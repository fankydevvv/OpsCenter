package com.opscenter.servicecatalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Input of {@code ServiceCatalogService.create} ({@code POST /api/v1/services}). The whole record is
 * hashed for the {@code Idempotency-Key} check (it holds no secret). The organization is the
 * single {@code DEFAULT} one (like teams in the base).
 */
public record CreateServiceCommand(String code, String name, String description, ServiceStatus status,
                                   UUID owningTeamId, UUID primaryOwnerId, UUID backupOwnerId,
                                   Map<String, Object> metadata, List<EnvironmentSpec> environments) {

    public CreateServiceCommand {
        environments = environments == null ? List.of() : List.copyOf(environments);
    }
}
