package com.opscenter.incident.application;

import java.util.UUID;

/** A person shown on an incident (assignee, actor of a timeline entry) - resolved through {@code UserLookup} (D-37). */
public record UserBrief(UUID id, String username, String displayName) {
}
