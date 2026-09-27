package com.opscenter.integration.domain;

/**
 * How a source proves who it is ({@code integration_sources.auth_type}). Sprint 2 implements
 * {@link #TOKEN} (shared bearer token - Alertmanager cannot sign its requests, D-39); HMAC signatures
 * are reserved for generic webhooks.
 */
public enum IntegrationAuthType {
    NONE,
    BASIC,
    TOKEN,
    HMAC,
    MTLS,
    DB_READONLY
}
