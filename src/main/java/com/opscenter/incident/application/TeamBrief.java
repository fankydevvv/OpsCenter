package com.opscenter.incident.application;

import java.util.UUID;

/** {@code {id, code, name}} of the owning team - resolved in batches through {@code TeamLookup} (D-37). */
public record TeamBrief(UUID id, String code, String name) {
}
