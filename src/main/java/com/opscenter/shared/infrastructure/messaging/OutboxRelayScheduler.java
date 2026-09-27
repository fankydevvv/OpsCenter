package com.opscenter.shared.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link OutboxRelay#relayOnce()} on a fixed delay.
 * <p>
 * Separated from the relay itself so the polling can be switched off with
 * {@code opscenter.outbox.relay.enabled=false} (tests, one-off tools) while the relay bean stays
 * available for explicit calls.
 */
@Component
@ConditionalOnProperty(prefix = "opscenter.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${opscenter.outbox.relay.delay:PT2S}", initialDelayString = "PT5S")
    public void poll() {
        try {
            relay.relayOnce();
        }
        catch (RuntimeException ex) {
            // Typically the database being unavailable; the next round will try again.
            log.warn("Outbox relay round failed: {}", ex.getMessage());
        }
    }
}
