package com.opscenter.incident.application;

import java.util.UUID;

/** {@code {id, code, name}} of the incident's service - resolved in batches through {@code ServiceLookup} (D-37). */
public record ServiceBrief(UUID id, String code, String name) {
}
