package com.opscenter.servicecatalog.application;

import java.util.Map;

import com.opscenter.servicecatalog.domain.ServiceStatus;

/**
 * Input of {@code PATCH /api/v1/services/{id}}: {@code null} = unchanged (D-35);
 * {@code description = ""} clears it; {@code active = false} is the soft delete (D-36);
 * {@code version} is the optimistic-lock token. Ownership is not here - see
 * {@link ReplaceOwnershipCommand}.
 */
public record UpdateServiceCommand(String name, String description, ServiceStatus status, Map<String, Object> metadata,
                                   Boolean active, long version) {
}
