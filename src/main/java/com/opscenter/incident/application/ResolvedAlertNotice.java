package com.opscenter.incident.application;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.incident.domain.IncidentSource;

/**
 * "This alert was resolved at the source" - sent by the alert module so the incident timeline can
 * say so (D-59). The incident status itself never changes automatically: RESOLVED needs a root
 * cause written by a person (01-SRS §7).
 */
public record ResolvedAlertNotice(UUID alertId, String alertName, String instance, Instant resolvedAt,
                                  IncidentSource source) {
}
