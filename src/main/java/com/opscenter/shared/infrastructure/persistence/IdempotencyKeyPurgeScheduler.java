package com.opscenter.shared.infrastructure.persistence;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly retention job for {@code idempotency_keys} (03-DB §25, §29).
 * <p>
 * Without it the table would grow forever and {@code expires_at} would be a column nobody reads.
 * Switched off with {@code opscenter.idempotency.purge.enabled=false} (tests call the store
 * directly).
 */
@Component
@ConditionalOnProperty(prefix = "opscenter.idempotency.purge", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class IdempotencyKeyPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeScheduler.class);

    private final IdempotencyKeyStore store;
    private final Clock clock;

    public IdempotencyKeyPurgeScheduler(IdempotencyKeyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${opscenter.idempotency.purge.delay:PT1H}", initialDelayString = "PT1M")
    public void purge() {
        try {
            int removed = store.purgeExpired(clock.instant());
            if (removed > 0) {
                log.info("Purged {} expired idempotency key(s)", removed);
            }
        }
        catch (RuntimeException ex) {
            log.warn("Idempotency key purge failed: {}", ex.getMessage());
        }
    }
}
