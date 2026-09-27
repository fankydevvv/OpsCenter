package com.opscenter.organization.application;

import java.util.UUID;

/**
 * The few facts other modules may know about a team (blueprint D-37): enough to validate an owner
 * and to render {@code {id, code, name}} in their DTOs, without exposing the {@code Team} entity.
 *
 * @param active {@code false} = soft-deleted team (03-DB §29)
 */
public record TeamRef(UUID id, UUID organizationId, String code, String name, boolean active) {
}
