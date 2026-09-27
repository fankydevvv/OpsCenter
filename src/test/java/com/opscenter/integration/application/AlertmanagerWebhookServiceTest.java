package com.opscenter.integration.application;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.opscenter.alert.application.AlertIngestionService;
import com.opscenter.alert.application.CanonicalAlertEvent;
import com.opscenter.alert.application.DeliveryContext;
import com.opscenter.alert.application.IngestionItem;
import com.opscenter.alert.application.IngestionResult;
import com.opscenter.alert.domain.AlertIngestionBusyException;
import com.opscenter.alert.domain.AlertSourceType;
import com.opscenter.alert.domain.AlertStatus;
import com.opscenter.alert.domain.IngestionOutcome;
import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.integration.application.alertmanager.AlertmanagerAlert;
import com.opscenter.integration.application.alertmanager.AlertmanagerEventMapper;
import com.opscenter.integration.application.alertmanager.AlertmanagerWebhook;
import com.opscenter.integration.domain.IntegrationRateLimitedException;
import com.opscenter.integration.domain.IntegrationSourceType;
import com.opscenter.servicecatalog.application.MappingStatus;
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
import com.opscenter.shared.domain.InvalidRequestException;
import com.opscenter.shared.domain.Severity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration of one delivery (blueprint §4.2, D-42, D-43, D-51, D-53) with the collaborators
 * mocked: storage outage, race retry, rate limit and the idempotency key rules.
 */
class AlertmanagerWebhookServiceTest {

    private static final IntegrationSourceRef SOURCE = new IntegrationSourceRef(UUID.randomUUID(), "alertmanager",
            UUID.randomUUID(), IntegrationSourceType.ALERTMANAGER);
    private static final byte[] BODY = "{\"alerts\":[]}".getBytes(StandardCharsets.UTF_8);

    private final AlertmanagerEventMapper mapper = mock(AlertmanagerEventMapper.class);
    private final AlertIngestionService ingestion = mock(AlertIngestionService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final IntegrationActivityRecorder activity = mock(IntegrationActivityRecorder.class);
    private final RequestContext requestContext = mock(RequestContext.class);

    private final AlertmanagerWebhookService service = new AlertmanagerWebhookService(mapper, ingestion, idempotency,
            storage, rateLimiter, audit, activity, requestContext,
            new IntegrationProperties(new IntegrationProperties.Alertmanager("token", 1_048_576, 2,
                    Duration.ofMinutes(15), 600, 20)),
            new SimpleMeterRegistry());

    private final AlertmanagerWebhook payload = new AlertmanagerWebhook("4", "g", 0, "firing", "r", Map.of(), Map.of(),
            Map.of(), "u", List.of(new AlertmanagerAlert("firing", Map.of("alertname", "A"), Map.of(), Instant.EPOCH,
                    null, null, null)));

    @BeforeEach
    void defaults() {
        when(requestContext.requestId()).thenReturn(Optional.of("req-1"));
        when(mapper.map(any(), any(), any(), any())).thenReturn(List.of(new CanonicalAlertEvent(0, UUID.randomUUID(),
                "alertmanager", AlertSourceType.ALERTMANAGER, AlertStatus.FIRING, Instant.EPOCH, "A", null, null, null,
                Severity.P3, true, null, "A", null, null, null, Instant.EPOCH, null, Map.of(), Map.of(), "req-1")));
        when(ingestion.ingest(any(), any())).thenAnswer(inv -> {
            DeliveryContext delivery = inv.getArgument(1);
            return new IngestionResult(delivery.deliveryId(), List.of(new IngestionItem(0, "fp", UUID.randomUUID(),
                    IngestionOutcome.CREATED, MappingStatus.UNMAPPED, UUID.randomUUID(), "INC-000001", "CREATED")),
                    delivery.archived());
        });
        when(idempotency.execute(any(UUID.class), anyString(), anyString(), anyString(), any(Duration.class), any()))
                .thenAnswer(inv -> {
                    IdempotentOperation<?> operation = inv.getArgument(5);
                    return IdempotentResult.created(operation.create());
                });
    }

    @Test
    void storageOutage_doesNotStopIngestion_theDeliveryIsJustNotArchived() {
        when(storage.putContentAddressed(anyString(), anyString(), any(), anyString(), anyMap()))
                .thenThrow(new ObjectStorageUnavailableException("MinIO down", null));

        WebhookDeliverySummary summary = service.receive(SOURCE, payload, BODY, null);

        assertThat(summary.archived()).isFalse();
        assertThat(summary.incidentsCreated()).isEqualTo(1);
        ArgumentCaptor<DeliveryContext> delivery = ArgumentCaptor.forClass(DeliveryContext.class);
        verify(ingestion).ingest(any(), delivery.capture());
        assertThat(delivery.getValue().rawRef()).isNull();
    }

    @Test
    void archivedDelivery_carriesTheReference_andTheBodyHashIsTheDefaultKey() {
        StoredObjectRef ref = new StoredObjectRef("opscenter-raw", "alertmanager/2026/09/27/x.json", "x", BODY.length);
        when(storage.putContentAddressed(eq("alertmanager"), eq(".json"), eq(BODY), eq("application/json"), anyMap()))
                .thenReturn(ref);

        WebhookDeliverySummary summary = service.receive(SOURCE, payload, BODY, "  ");

        assertThat(summary.archived()).isTrue();
        String hash = ContentHashes.sha256Hex(BODY);
        verify(idempotency).execute(eq(SOURCE.id()), eq("sha256:" + hash), eq(hash), eq("AlertmanagerDelivery"),
                eq(Duration.ofMinutes(15)), any());
        verify(activity).touch(SOURCE.id());
    }

    @Test
    void explicitDeliveryKey_isUsedAsIdempotencyKey() {
        service.receive(SOURCE, payload, BODY, " am-42 ");
        verify(idempotency).execute(eq(SOURCE.id()), eq("am-42"), anyString(), anyString(), any(Duration.class), any());
    }

    /** What Spring/Hibernate raise when {@code uk_alerts_firing_fingerprint} rejects a concurrent insert. */
    private static DataIntegrityViolationException lostRace() {
        return new DataIntegrityViolationException("duplicate key", new ConstraintViolationException("duplicate key",
                new SQLException("duplicate key value violates unique constraint", "23505"),
                "uk_alerts_firing_fingerprint"));
    }

    @Test
    void lostRace_isRetriedExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        when(idempotency.execute(any(UUID.class), anyString(), anyString(), anyString(), any(Duration.class), any()))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() == 1) {
                        throw lostRace();
                    }
                    IdempotentOperation<?> operation = inv.getArgument(5);
                    return IdempotentResult.created(operation.create());
                });

        WebhookDeliverySummary summary = service.receive(SOURCE, payload, BODY, null);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(summary.items()).hasSize(1);
    }

    @Test
    void secondLostRace_isNotRetriedForever_andAnswers503SoAlertmanagerRetriesLater() {
        when(idempotency.execute(any(UUID.class), anyString(), anyString(), anyString(), any(Duration.class), any()))
                .thenAnswer(inv -> { throw lostRace(); });

        assertThatThrownBy(() -> service.receive(SOURCE, payload, BODY, null))
                .isInstanceOf(AlertIngestionBusyException.class);
        verify(idempotency, times(2)).execute(any(UUID.class), anyString(), anyString(), anyString(),
                any(Duration.class), any());
    }

    @Test
    void deterministicDataError_isNotRetried_andNeverBecomesA4xx() {
        // e.g. SQLState 22001 "value too long": retrying fails the same way; a 409 would make
        // Alertmanager drop the delivery for good (review finding), a 500 keeps it retried.
        when(idempotency.execute(any(UUID.class), anyString(), anyString(), anyString(), any(Duration.class), any()))
                .thenThrow(new DataIntegrityViolationException("value too long",
                        new SQLException("value too long for type character varying(50)", "22001")));

        assertThatThrownBy(() -> service.receive(SOURCE, payload, BODY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("22001");
        verify(idempotency, times(1)).execute(any(UUID.class), anyString(), anyString(), anyString(),
                any(Duration.class), any());
    }

    @Test
    void tooManyAlerts_orTooLongKey_areRejectedBeforeAnythingIsStored() {
        AlertmanagerWebhook three = new AlertmanagerWebhook("4", "g", 0, "firing", "r", Map.of(), Map.of(), Map.of(),
                "u", List.of(payload.alerts().getFirst(), payload.alerts().getFirst(), payload.alerts().getFirst()));

        assertThatThrownBy(() -> service.receive(SOURCE, three, BODY, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.receive(SOURCE, payload, BODY, "k".repeat(201)))
                .isInstanceOf(InvalidRequestException.class);
        verify(storage, never()).putContentAddressed(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    void admit_answers429_whenTheSourceExceedsItsRate_butFailsOpenWhenRedisIsDown() {
        when(rateLimiter.tryAcquire(eq("webhook:alertmanager"), anyInt(), any()))
                .thenReturn(new RateLimitDecision(false, 601, 600, Duration.ofSeconds(12), false));
        assertThatThrownBy(() -> service.admit(SOURCE)).isInstanceOf(IntegrationRateLimitedException.class)
                .hasMessageContaining("12 s");

        when(rateLimiter.tryAcquire(eq("webhook:alertmanager"), anyInt(), any()))
                .thenReturn(RateLimitDecision.failOpen(600));
        service.admit(SOURCE);
    }
}
