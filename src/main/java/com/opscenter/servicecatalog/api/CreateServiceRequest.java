package com.opscenter.servicecatalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.opscenter.servicecatalog.application.CreateServiceCommand;
import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Body of {@code POST /api/v1/services} (04-API §5, blueprint §7.1). {@code code} is stored lower
 * case and can never change afterwards (D-33); environments may be created in the same request.
 */
public record CreateServiceRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = ApiPatterns.SERVICE_CODE, message = ApiPatterns.SERVICE_CODE_MESSAGE)
        String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        ServiceStatus status,
        UUID owningTeamId,
        UUID primaryOwnerId,
        UUID backupOwnerId,
        Map<String, Object> metadata,
        @Size(max = 20) List<@NotNull @Valid EnvironmentRequest> environments) {

    public CreateServiceCommand toCommand() {
        return new CreateServiceCommand(code, name, description, status, owningTeamId, primaryOwnerId, backupOwnerId,
                metadata, environments == null ? List.of() : environments.stream().map(EnvironmentRequest::toSpec).toList());
    }
}
