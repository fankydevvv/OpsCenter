package com.opscenter.shared.infrastructure.persistence;

/** Lifecycle of an {@code outbox_events} row (03-DB §23, CHECK constraint in V004). */
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
