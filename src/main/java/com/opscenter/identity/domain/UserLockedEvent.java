package com.opscenter.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of the {@code UserLocked} domain event written to the transactional outbox when an
 * administrator locks an account (D-14: the base ships one real event so the outbox -> RabbitMQ
 * path is exercised end to end; routing key {@code user.locked}).
 * <p>
 * Contains identifiers and the reason only - never a hash or token - because the payload leaves
 * the system.
 */
public record UserLockedEvent(UUID userId, String username, String reason, UUID lockedBy, Instant occurredAt) {

    public static final String EVENT_TYPE = "UserLocked";
    public static final String AGGREGATE_TYPE = "User";
}
