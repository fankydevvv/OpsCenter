package com.opscenter.shared.api;

import com.opscenter.shared.application.status.SystemStatusService;
import com.opscenter.shared.application.status.SystemStatusView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/system/status} (blueprint §7.6, D-63): name, status, version and latency of
 * backend, PostgreSQL, Redis, RabbitMQ, MinIO, Prometheus and Alertmanager for the
 * {@code /admin/system} page.
 * <p>
 * Unlike {@code /actuator/health} (public, a yes/no for load balancers and compose healthchecks)
 * this endpoint reveals versions and internal addresses, so it needs {@code system.read} (ADMIN).
 * It always answers {@code 200}: a component being down is data, not an error of this request.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final SystemStatusService systemStatus;

    public SystemStatusController(SystemStatusService systemStatus) {
        this.systemStatus = systemStatus;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('system.read')")
    public SystemStatusView status() {
        return systemStatus.check();
    }
}
