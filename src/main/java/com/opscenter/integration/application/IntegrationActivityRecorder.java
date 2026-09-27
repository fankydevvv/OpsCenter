package com.opscenter.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.opscenter.integration.infrastructure.IntegrationSourceRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains {@code integration_sources.last_event_at} ("last synchronization", 01-SRS §14), shown as
 * {@code lastWebhookAt} on {@code /admin/system}.
 * <p>
 * Written <em>after</em> the business transaction, in its own short transaction and at most every
 * 10 seconds per source: updating the source row inside every delivery would make all deliveries of
 * the source wait for each other on that single row lock - a hot spot that buys nothing, because the
 * value is informational.
 */
@Component
public class IntegrationActivityRecorder {

    static final Duration THROTTLE = Duration.ofSeconds(10);

    private final IntegrationSourceRepository sources;
    private final Clock clock;
    private final Map<UUID, Instant> lastWritten = new ConcurrentHashMap<>();

    public IntegrationActivityRecorder(IntegrationSourceRepository sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(UUID sourceId) {
        Instant now = clock.instant();
        Instant previous = lastWritten.get(sourceId);
        if (previous != null && Duration.between(previous, now).compareTo(THROTTLE) < 0) {
            return;
        }
        lastWritten.put(sourceId, now);
        sources.touchLastEvent(sourceId, now);
    }
}
