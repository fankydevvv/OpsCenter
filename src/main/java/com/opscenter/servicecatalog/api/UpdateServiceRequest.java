package com.opscenter.servicecatalog.api;

import java.util.Map;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.opscenter.servicecatalog.application.UpdateServiceCommand;
import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Body of {@code PATCH /api/v1/services/{id}}: omitted fields stay unchanged, {@code description: ""}
 * clears the description, {@code active: false} soft-deletes the service (D-36); {@code version} is
 * mandatory (optimistic locking, 03-DB §28).
 */
public record UpdateServiceRequest(
        @Size(min = 1, max = 255) String name,
        @Size(max = 4000) String description,
        ServiceStatus status,
        Map<String, Object> metadata,
        Boolean active,
        @NotNull Long version) {

    public UpdateServiceCommand toCommand() {
        return new UpdateServiceCommand(name, description, status, metadata, active, version);
    }
}
