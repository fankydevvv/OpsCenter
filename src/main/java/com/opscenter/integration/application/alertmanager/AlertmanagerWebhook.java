package com.opscenter.integration.application.alertmanager;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The Alertmanager webhook payload, version 4 (the {@code webhook_config} format of
 * prometheus/alertmanager; blueprint §7.2). Only the fields OpsCenter uses are declared - unknown
 * properties are ignored ({@code FAIL_ON_UNKNOWN_PROPERTIES} is off), so a newer Alertmanager adding
 * fields does not break the integration.
 * <p>
 * One delivery = one notification of one alert <em>group</em>: every alert currently in the group,
 * firing and resolved ones alike. That is why the same alert shows up again and again - which is
 * exactly what deduplication absorbs (TC-DEDUP-001).
 *
 * @param alerts 1..500 alerts (the maximum is also configurable, lower only)
 */
public record AlertmanagerWebhook(
        String version,
        String groupKey,
        Integer truncatedAlerts,
        String status,
        String receiver,
        Map<String, String> groupLabels,
        Map<String, String> commonLabels,
        Map<String, String> commonAnnotations,
        String externalURL,
        @NotEmpty @Size(max = 500) List<@Valid @NotNull AlertmanagerAlert> alerts) {
}
