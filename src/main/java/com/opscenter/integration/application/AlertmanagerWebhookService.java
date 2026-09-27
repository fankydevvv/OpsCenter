package com.opscenter.integration.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.opscenter.alert.application.AlertIngestionService;
import com.opscenter.alert.application.CanonicalAlertEvent;
import com.opscenter.alert.application.DeliveryContext;
import com.opscenter.alert.application.IngestionRaces;
import com.opscenter.alert.application.IngestionResult;
import com.opscenter.alert.domain.AlertIngestionBusyException;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.integration.application.alertmanager.AlertmanagerEventMapper;
import com.opscenter.integration.application.alertmanager.AlertmanagerWebhook;
import com.opscenter.integration.domain.IntegrationAuditActions;
import com.opscenter.integration.domain.IntegrationRateLimitedException;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.application.RequestContext;
import com.opscenter.shared.application.ratelimit.RateLimitDecision;
import com.opscenter.shared.application.ratelimit.RateLimiter;
import com.opscenter.shared.application.storage.ContentHashes;
import com.opscenter.shared.application.storage.ObjectStorage;
import com.opscenter.shared.application.storage.ObjectStorageUnavailableException;
import com.opscenter.shared.application.storage.StoredObjectRef;
import com.opscenter.shared.domain.DomainException;
import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Use case "receive an Alertmanager delivery" (FR-ALT-01, 04-API §6, blueprint §4.2).
 * <p>
 * The order of the steps is deliberate - cheapest checks first, evidence before processing:
 * <ol>
 *   <li>(filter) authenticate the shared token in constant time - 401/503;</li>
 *   <li>{@link #admit}: rate limit per source (Redis counter, fail-open) - 429;</li>
 *   <li>(controller) read at most 1 MiB, parse, validate - 413/400;</li>
 *   <li>{@link #receive}: archive the verbatim body in object storage <em>outside</em> any
 *       transaction (no database connection is held during network I/O). A storage outage only
 *       means {@code archived=false} - the alert is never dropped because of it (D-42);</li>
 *   <li>normalise to canonical events;</li>
 *   <li>idempotency (D-43): key = {@code Idempotency-Key} / {@code X-Webhook-Id} header, otherwise
 *       {@code sha256:<hex of the body>}. Alertmanager retries a failed delivery with the same body,
 *       so the hash is a natural idempotency key: a retry is answered from the stored result
 *       ({@code replayed=true}) and creates nothing (TC-ALT-004, TC-IDEMP-001);</li>
 *   <li>one business transaction: alerts, occurrences, incidents, timeline, audit, outbox;</li>
 *   <li>after commit: {@code last_event_at} (throttled).</li>
 * </ol>
 * If the final database guard fires (a unique index rejects a duplicate firing alert / open incident
 * because the Redis lock was lost - R-23), the whole delivery is retried <b>once</b>: the idempotency
 * key was marked FAILED by the rollback, the retry reclaims it and now takes the dedup/link path (D-51).
 * Any other data error is not a race: it is logged with its SQLState and answered 500 (never retried
 * here, never a 4xx that Alertmanager would drop).
 */
@Service
public class AlertmanagerWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookService.class);

    /** {@code idempotency_keys.resource_type} of a webhook delivery. */
    static final String RESOURCE_TYPE = "AlertmanagerDelivery";
    static final String ARCHIVE_PREFIX = "alertmanager";
    static final int MAX_KEY_LENGTH = 200;

    private final AlertmanagerEventMapper mapper;
    private final AlertIngestionService ingestion;
    private final IdempotencyService idempotency;
    private final ObjectStorage objectStorage;
    private final RateLimiter rateLimiter;
    private final AuditRecorder audit;
    private final IntegrationActivityRecorder activity;
    private final RequestContext requestContext;
    private final IntegrationProperties.Alertmanager properties;
    private final MeterRegistry meters;

    public AlertmanagerWebhookService(AlertmanagerEventMapper mapper, AlertIngestionService ingestion,
                                      IdempotencyService idempotency, ObjectStorage objectStorage,
                                      RateLimiter rateLimiter, AuditRecorder audit,
                                      IntegrationActivityRecorder activity, RequestContext requestContext,
                                      IntegrationProperties properties, MeterRegistry meters) {
        this.mapper = mapper;
        this.ingestion = ingestion;
        this.idempotency = idempotency;
        this.objectStorage = objectStorage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.activity = activity;
        this.requestContext = requestContext;
        this.properties = properties.alertmanager();
        this.meters = meters;
    }

    /** Largest body the controller reads (413 above it). */
    public int maxPayloadBytes() {
        return properties.maxPayloadBytes();
    }

    /**
     * Step 2: fixed-window rate limit per source (D-53). Fail-open - a Redis outage never blocks
     * real alerts.
     *
     * @throws IntegrationRateLimitedException 429
     */
    public void admit(IntegrationSourceRef source) {
        RateLimitDecision decision = rateLimiter.tryAcquire("webhook:" + source.code(),
                properties.rateLimitPerMinute(), Duration.ofMinutes(1));
        if (!decision.allowed()) {
            count(source, "rate_limited");
            throw new IntegrationRateLimitedException("More than " + properties.rateLimitPerMinute()
                    + " deliveries per minute from '" + source.code() + "'; retry in "
                    + Math.max(1, decision.retryAfter().toSeconds()) + " s");
        }
    }

    /**
     * Steps 4-8 for an authenticated, parsed and validated delivery.
     *
     * @param rawBody     the exact bytes received (archived, hashed)
     * @param deliveryKey {@code Idempotency-Key} or {@code X-Webhook-Id} header, may be {@code null}
     */
    public WebhookDeliverySummary receive(IntegrationSourceRef source, AlertmanagerWebhook payload, byte[] rawBody,
                                          String deliveryKey) {
        if (payload.alerts().size() > properties.maxAlertsPerRequest()) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    "alerts: at most " + properties.maxAlertsPerRequest() + " alerts per delivery");
        }
        if (deliveryKey != null && deliveryKey.strip().length() > MAX_KEY_LENGTH) {
            throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                    "Idempotency-Key / X-Webhook-Id must be at most " + MAX_KEY_LENGTH + " characters");
        }
        String requestId = requestContext.requestId().orElse(null);
        String bodyHash = ContentHashes.sha256Hex(rawBody);
        String key = deliveryKey == null || deliveryKey.isBlank() ? "sha256:" + bodyHash : deliveryKey.strip();

        StoredObjectRef rawRef = archive(source, rawBody, requestId);
        List<CanonicalAlertEvent> events = mapper.map(payload, source, requestId, key);
        DeliveryContext delivery = new DeliveryContext(UUID.randomUUID(), source.id(), source.code(),
                source.organizationId(), AlertSourceType.ALERTMANAGER, rawRef);

        IdempotentResult<IngestionResult> result;
        try {
            result = processWithOneRaceRetry(source, key, bodyHash, events, delivery);
        }
        catch (DomainException rejected) {
            count(source, rejected.code().toLowerCase(Locale.ROOT));
            throw rejected;
        }

        try {
            activity.touch(source.id());
        }
        catch (RuntimeException ex) {
            log.warn("Could not update last_event_at of '{}': {}", source.code(), ex.getClass().getSimpleName());
        }
        count(source, result.replayed() ? "replayed" : "accepted");
        return WebhookDeliverySummary.of(result.value(), result.replayed());
    }

    /**
     * D-51: a unique index / version check may catch a race the lock should have prevented. Only
     * such a lost race is retried (once - everything was rolled back, the key marked FAILED, and the
     * retry sees the committed winner). A deterministic data error is never retried and never
     * answered with a 4xx, because Alertmanager drops 4xx deliveries for good (see {@link IngestionRaces}).
     */
    private IdempotentResult<IngestionResult> processWithOneRaceRetry(IntegrationSourceRef source, String key,
                                                                      String bodyHash, List<CanonicalAlertEvent> events,
                                                                      DeliveryContext delivery) {
        try {
            return process(source, key, bodyHash, events, delivery);
        }
        catch (DataIntegrityViolationException | OptimisticLockingFailureException failure) {
            if (!IngestionRaces.isLostRace(failure)) {
                throw dataError(source, delivery, failure);
            }
            log.info("Delivery {} from '{}' lost a concurrency race ({}); retrying once", delivery.deliveryId(),
                    source.code(), describe(failure));
        }
        try {
            return process(source, key, bodyHash, events, delivery);
        }
        catch (DataIntegrityViolationException | OptimisticLockingFailureException failure) {
            if (!IngestionRaces.isLostRace(failure)) {
                throw dataError(source, delivery, failure);
            }
            // Still contended after one retry: 503 lets Alertmanager retry later with backoff.
            throw new AlertIngestionBusyException("Delivery kept colliding with a concurrent delivery of the same "
                    + "alert group; retry shortly");
        }
    }

    /**
     * A data error that is not a race is a bug (e.g. a value longer than its column). It is logged
     * with its SQLState and answered 500 - Alertmanager retries 5xx, so once the bug is fixed the
     * delivery still arrives instead of being silently dropped after a 409.
     */
    private IllegalStateException dataError(IntegrationSourceRef source, DeliveryContext delivery,
                                            RuntimeException failure) {
        count(source, "data_error");
        log.error("Delivery {} from '{}' could not be stored: {} - not retried", delivery.deliveryId(), source.code(),
                describe(failure), failure);
        return new IllegalStateException("Delivery " + delivery.deliveryId() + " could not be stored ("
                + describe(failure) + ")", failure);
    }

    private static String describe(RuntimeException failure) {
        String state = IngestionRaces.sqlState(failure);
        String constraint = IngestionRaces.constraintName(failure);
        return failure.getClass().getSimpleName() + (state == null ? "" : ", SQLState " + state)
                + (constraint == null ? "" : ", constraint " + constraint);
    }

    private IdempotentResult<IngestionResult> process(IntegrationSourceRef source, String key, String bodyHash,
                                                      List<CanonicalAlertEvent> events, DeliveryContext delivery) {
        return idempotency.execute(source.id(), key, bodyHash, RESOURCE_TYPE, properties.idempotencyTtl(),
                new IdempotentOperation<>() {
                    @Override
                    public IngestionResult create() {
                        IngestionResult result = ingestion.ingest(events, delivery);
                        audit.record(IntegrationAuditActions.INTEGRATION_EVENT_RECEIVED, "IntegrationSource",
                                source.id(), null, auditSummary(result, delivery), null, source.organizationId());
                        return result;
                    }

                    @Override
                    public UUID resourceId(IngestionResult created) {
                        return created.deliveryId();
                    }

                    @Override
                    public IngestionResult reload(UUID deliveryId) {
                        return ingestion.rebuild(deliveryId);
                    }
                });
    }

    /**
     * D-42: content-addressed archive ({@code alertmanager/yyyy/MM/dd/<sha256>.json}); a retry of the
     * same body rewrites the same object instead of creating a copy.
     */
    private StoredObjectRef archive(IntegrationSourceRef source, byte[] rawBody, String requestId) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", source.code());
        if (requestId != null) {
            metadata.put("request-id", requestId);
        }
        try {
            StoredObjectRef ref = objectStorage.putContentAddressed(ARCHIVE_PREFIX, ".json", rawBody,
                    "application/json", metadata);
            archiveCounter("stored");
            return ref;
        }
        catch (ObjectStorageUnavailableException ex) {
            archiveCounter("failed");
            log.warn("Raw webhook of '{}' not archived ({}); ingestion continues with archived=false",
                    source.code(), ex.getMessage());
            return null;
        }
    }

    private static Map<String, Object> auditSummary(IngestionResult result, DeliveryContext delivery) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("deliveryId", delivery.deliveryId());
        summary.put("received", result.items().size());
        summary.put("alertsCreated", result.count(IngestionOutcome.CREATED, IngestionOutcome.REFIRED,
                IngestionOutcome.RESOLVED_UNKNOWN));
        summary.put("alertsDeduplicated", result.count(IngestionOutcome.DEDUPLICATED));
        summary.put("alertsResolved", result.count(IngestionOutcome.RESOLVED));
        summary.put("unmappedAlerts", result.unmapped());
        summary.put("archived", delivery.archived());
        if (delivery.rawRef() != null) {
            summary.put("rawKey", delivery.rawRef().key());
        }
        return summary;
    }

    private void count(IntegrationSourceRef source, String result) {
        Counter.builder("opscenter.webhook.requests")
                .description("Inbound webhook deliveries by result (blueprint §9.4)")
                .tag("source", source.code())
                .tag("result", result)
                .register(meters)
                .increment();
    }

    private void archiveCounter(String result) {
        Counter.builder("opscenter.object.storage.archive")
                .description("Raw webhook archive attempts (D-42)")
                .tag("result", result)
                .register(meters)
                .increment();
    }
}
