package com.opscenter.servicecatalog.domain;

import java.util.Objects;
import java.util.UUID;

import com.opscenter.shared.domain.InvalidRequestException;

/**
 * Value object describing one additional owner before it becomes a {@link ServiceOwner} row.
 * The constructor enforces the rule the database checks with
 * {@code num_nonnulls(team_id, user_id) = 1} (D-34): exactly one target, so the client gets a clear
 * {@code 400 SERVICE_OWNER_TARGET_INVALID} instead of a constraint violation.
 *
 * @param priority 1 (first to contact) .. 100; {@code null} in the request means 1
 */
public record ServiceOwnerSpec(UUID teamId, UUID userId, OwnershipType ownershipType, int priority) {

    public ServiceOwnerSpec {
        if ((teamId == null) == (userId == null)) {
            throw new InvalidRequestException(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID,
                    "An additional owner must reference exactly one of teamId or userId");
        }
        Objects.requireNonNull(ownershipType, "ownershipType");
        if (priority < 1 || priority > 100) {
            throw new InvalidRequestException(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID,
                    "Owner priority must be between 1 and 100");
        }
    }

    /** Identity of the entry for duplicate detection: same target + same ownership type. */
    public String targetKey() {
        return (teamId != null ? "team:" + teamId : "user:" + userId) + "/" + ownershipType;
    }
}
