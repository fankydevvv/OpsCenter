package com.opscenter.incident.api;

import com.opscenter.incident.application.OperationsSummary;
import com.opscenter.incident.application.OperationsSummaryService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/operations/summary} - KPIs of the Operations Center (02-SAD §18, blueprint
 * §7.5, additive endpoint). Needs {@code incident.read}; the alert blocks are only filled when the
 * caller also holds {@code alert.read}.
 */
@RestController
public class OperationsSummaryController {

    private final OperationsSummaryService summaries;

    public OperationsSummaryController(OperationsSummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/api/v1/operations/summary")
    @PreAuthorize("hasAuthority('incident.read')")
    public OperationsSummary summary(Authentication authentication) {
        return summaries.summary(CallerPermissions.of(authentication));
    }
}
