package com.opscenter.shared.infrastructure.persistence;

/** Lifecycle of an {@code idempotency_keys} row (03-DB §22, CHECK constraint in V004). */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
