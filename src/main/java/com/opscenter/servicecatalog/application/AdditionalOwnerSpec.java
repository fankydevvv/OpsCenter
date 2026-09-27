package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.servicecatalog.domain.OwnershipType;

/** One entry of {@code additionalOwners[]} in {@code PUT /services/{id}/ownership}; exactly one of teamId/userId. */
public record AdditionalOwnerSpec(UUID teamId, UUID userId, OwnershipType ownershipType, Integer priority) {
}
