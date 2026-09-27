package com.opscenter.shared.application.status;

/**
 * Health of one component in {@code GET /api/v1/system/status} (D-63).
 * {@code UNKNOWN} = not checked (probe not configured, e.g. no Prometheus URL), which is different
 * from {@code DOWN} = checked and failed.
 */
public enum ComponentState {
    UP,
    DOWN,
    UNKNOWN
}
