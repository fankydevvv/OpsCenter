package com.opscenter.integration.domain;

/** Error codes of inbound integrations (04-API §17, blueprint §7.7). */
public final class IntegrationErrorCodes {

    /** 401 - missing or wrong shared token (D-39). */
    public static final String INTEGRATION_AUTH_FAILED = "INTEGRATION_AUTH_FAILED";
    /** 429 - too many deliveries per minute, or too many failed authentications from one address (D-53). */
    public static final String INTEGRATION_RATE_LIMITED = "INTEGRATION_RATE_LIMITED";
    /** 503 - the source is unknown, disabled or has no usable token configured (04-API §17). */
    public static final String INTEGRATION_UNAVAILABLE = "INTEGRATION_UNAVAILABLE";
    /** 413 - body larger than {@code opscenter.integration.alertmanager.max-payload-bytes}. */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    private IntegrationErrorCodes() {
    }
}
