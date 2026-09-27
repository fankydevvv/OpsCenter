package com.opscenter.shared.application.status;

import java.util.Map;

/**
 * One entry of {@code components[]} in {@code GET /api/v1/system/status} (blueprint §7.6).
 *
 * @param name      component name
 * @param status    UP / DOWN / UNKNOWN
 * @param version   product version if known
 * @param latencyMs how long the probe took (wall clock)
 * @param details   non-secret facts
 * @param error     short reason when not UP
 */
public record ComponentStatus(String name, ComponentState status, String version, Long latencyMs,
                              Map<String, Object> details, String error) {

    public ComponentStatus {
        details = details == null ? Map.of() : details;
    }
}
