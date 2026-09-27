package com.opscenter.integration.domain;

/** Result of the last connectivity check of a source ({@code integration_sources.health_status}). */
public enum IntegrationHealthStatus {
    UNKNOWN,
    CONNECTED,
    FAILED
}
