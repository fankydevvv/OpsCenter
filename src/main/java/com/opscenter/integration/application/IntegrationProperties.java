package com.opscenter.integration.application;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code opscenter.integration.*} (blueprint §11, D-39, D-43, D-53).
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.integration")
public record IntegrationProperties(@Valid @DefaultValue Alertmanager alertmanager) {

    /**
     * @param token                     the shared secret behind {@code secret_ref env:OPSCENTER_ALERTMANAGER_TOKEN}
     *                                  (bound from that environment variable in {@code application.yml});
     *                                  blank = the webhook answers 503 INTEGRATION_UNAVAILABLE
     * @param maxPayloadBytes           413 above this size (1 MiB)
     * @param maxAlertsPerRequest       400 above this many alerts in one delivery
     * @param idempotencyTtl            how long a delivery id replays its answer (D-43)
     * @param rateLimitPerMinute        deliveries per minute per source before 429 (D-53)
     * @param authFailureLimitPerMinute failed authentications per minute per client address before 429
     */
    public record Alertmanager(
            String token,
            @Min(1024) @DefaultValue("1048576") int maxPayloadBytes,
            @Min(1) @Max(500) @DefaultValue("500") int maxAlertsPerRequest,
            @NotNull @DefaultValue("PT15M") Duration idempotencyTtl,
            @Min(1) @DefaultValue("600") int rateLimitPerMinute,
            @Min(1) @DefaultValue("20") int authFailureLimitPerMinute) {

        /** Never print the token (e.g. in a failed-binding report or a debug log). */
        @Override
        public String toString() {
            return "Alertmanager[token=" + (token == null || token.isBlank() ? "<unset>" : "****")
                    + ", maxPayloadBytes=" + maxPayloadBytes + ", maxAlertsPerRequest=" + maxAlertsPerRequest
                    + ", idempotencyTtl=" + idempotencyTtl + ", rateLimitPerMinute=" + rateLimitPerMinute
                    + ", authFailureLimitPerMinute=" + authFailureLimitPerMinute + "]";
        }
    }
}
