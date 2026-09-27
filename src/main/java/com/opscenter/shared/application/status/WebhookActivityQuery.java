package com.opscenter.shared.application.status;

import java.time.Instant;
import java.util.Optional;

/**
 * Optional port answering "when did an inbound integration last deliver an event?" - shown as
 * {@code lastWebhookAt} on the Alertmanager card of {@code /admin/system} (D-63).
 * <p>
 * The shared kernel cannot read the integration module's tables (modules depend on shared, never
 * the reverse), so the integration module implements this interface over
 * {@code integration_sources.last_event_at}. Until it does, the detail is simply omitted.
 */
public interface WebhookActivityQuery {

    /** @param sourceCode {@code integration_sources.code}, e.g. {@code alertmanager} */
    Optional<Instant> lastEventAt(String sourceCode);
}
