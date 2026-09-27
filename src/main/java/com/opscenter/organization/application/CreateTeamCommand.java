package com.opscenter.organization.application;

import java.util.UUID;

/**
 * Input of {@code TeamService.create}. {@code organizationId} may be {@code null}: the base is
 * single-tenant and falls back to the seeded {@code DEFAULT} organization. The whole record is
 * hashed for the {@code Idempotency-Key} check - it holds no secret.
 */
public record CreateTeamCommand(String code, String name, String description, String teamType, UUID organizationId) {
}
