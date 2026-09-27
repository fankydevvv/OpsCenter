package com.opscenter.servicecatalog.application;

import java.util.List;
import java.util.UUID;

/**
 * Input of {@code PUT /api/v1/services/{id}/ownership} (blueprint D-35): the <b>complete</b> new
 * ownership. {@code null} removes an owner and an empty list removes all additional owners - PUT
 * replaces, it never merges. A dedicated endpoint exists because a Java record cannot tell "field
 * absent" from "field null" in a PATCH body, and "remove the backup owner" must be expressible.
 */
public record ReplaceOwnershipCommand(UUID owningTeamId, UUID primaryOwnerId, UUID backupOwnerId,
                                      List<AdditionalOwnerSpec> additionalOwners, long version) {

    public ReplaceOwnershipCommand {
        additionalOwners = additionalOwners == null ? List.of() : List.copyOf(additionalOwners);
    }
}
