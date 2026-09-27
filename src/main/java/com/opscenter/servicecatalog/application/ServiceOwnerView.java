package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.servicecatalog.domain.OwnershipType;

/**
 * One additional owner in {@code ServiceDetail.owners[]}: exactly one of {@code team} / {@code user}
 * is set (D-34).
 */
public record ServiceOwnerView(UUID id, OwnershipType ownershipType, int priority, TeamBrief team, UserBrief user) {
}
