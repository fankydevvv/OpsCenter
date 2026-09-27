package com.opscenter.shared.application.status;

/**
 * Summary of all components (D-63): {@code DOWN} only when PostgreSQL - the source of truth - is
 * down; {@code DEGRADED} when any other component is down (it has a fallback: Redis -> advisory
 * lock, MinIO -> archived=false, RabbitMQ -> outbox keeps events); otherwise {@code UP}.
 */
public enum OverallStatus {
    UP,
    DEGRADED,
    DOWN
}
