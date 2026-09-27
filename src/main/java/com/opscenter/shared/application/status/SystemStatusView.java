package com.opscenter.shared.application.status;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /api/v1/system/status} (blueprint §7.6), rendered by the {@code /admin/system}
 * page.
 */
public record SystemStatusView(Instant checkedAt, OverallStatus overallStatus, List<ComponentStatus> components) {

    public SystemStatusView {
        components = components == null ? List.of() : List.copyOf(components);
    }
}
