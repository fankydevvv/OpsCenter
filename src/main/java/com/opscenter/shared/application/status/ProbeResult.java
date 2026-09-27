package com.opscenter.shared.application.status;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a {@link ComponentProbe} found out. Name and latency are added by {@link SystemStatusService},
 * so a probe only reports facts about its component.
 *
 * @param state   UP / DOWN / UNKNOWN
 * @param version product version when the component exposes one ({@code null} otherwise - S3 does not)
 * @param details small, non-secret facts (Flyway version, targets up/down, outbox backlog ...)
 * @param error   short reason for DOWN/UNKNOWN; never a stack trace or a credential
 */
public record ProbeResult(ComponentState state, String version, Map<String, Object> details, String error) {

    public ProbeResult {
        // LinkedHashMap keeps the order the probe chose; nulls are allowed in details
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static ProbeResult up(String version, Map<String, Object> details) {
        return new ProbeResult(ComponentState.UP, version, details, null);
    }

    public static ProbeResult down(String error, Map<String, Object> details) {
        return new ProbeResult(ComponentState.DOWN, null, details, error);
    }

    public static ProbeResult unknown(String reason) {
        return new ProbeResult(ComponentState.UNKNOWN, null, Map.of(), reason);
    }
}
