package com.opscenter.shared.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.opscenter.shared.infrastructure.persistence.OutboxEventEntity;
import com.opscenter.shared.infrastructure.persistence.OutboxEventRepository;
import com.opscenter.shared.infrastructure.persistence.OutboxEventStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes {@code PENDING} outbox rows to RabbitMQ (03-DB §23 "Outbox Publisher", D-14, D-28).
 * <p>
 * One round = one database transaction: lock the due batch ({@code FOR UPDATE SKIP LOCKED}),
 * publish each event, mark it {@code PUBLISHED} or bump {@code retry_count}/{@code last_error} and
 * push {@code next_attempt_at} out with exponential backoff. A broker outage therefore never loses
 * an event - the row simply stays {@code PENDING} and is retried later (TC-OUTBOX-002) until
 * {@code max-retries} (hours, not seconds) parks it as {@code FAILED} for an operator.
 * <p>
 * When the broker cannot be <em>connected</em> at all, the round stops after the first event:
 * every further event would pay the same connection timeout while the batch's row locks and the
 * database connection stay held. Ordering is therefore best-effort per round, not guaranteed per
 * aggregate; consumers must be idempotent anyway (at-least-once, 04-API §15).
 * <p>
 * The transaction is programmatic ({@link TransactionTemplate}) rather than {@code @Transactional}
 * so the class also works when constructed by hand in a test with a deliberately broken
 * {@code RabbitTemplate}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";
    public static final String HEADER_AGGREGATE_TYPE = "aggregateType";
    public static final String HEADER_AGGREGATE_ID = "aggregateId";
    public static final String HEADER_OCCURRED_AT = "occurredAt";

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
                       TransactionTemplate transactionTemplate, OutboxProperties properties, Clock clock) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one polling round.
     *
     * @return number of events published successfully in this round
     */
    public int relayOnce() {
        Integer published = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<OutboxEventEntity> batch = repository.lockNextBatch(OutboxEventStatus.PENDING, now,
                    Limit.of(properties.batchSize()));
            int ok = 0;
            for (OutboxEventEntity event : batch) {
                PublishResult result = publish(event, now);
                if (result == PublishResult.PUBLISHED) {
                    ok++;
                }
                else if (result == PublishResult.BROKER_UNREACHABLE) {
                    break;
                }
            }
            return ok;
        });
        return published == null ? 0 : published;
    }

    private enum PublishResult { PUBLISHED, FAILED, BROKER_UNREACHABLE }

    private PublishResult publish(OutboxEventEntity event, Instant now) {
        try {
            rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, event.routingKey(), toMessage(event));
            event.markPublished(now);
            log.debug("Published outbox event {} ({})", event.getId(), event.getEventType());
            return PublishResult.PUBLISHED;
        }
        catch (RuntimeException ex) {
            event.markAttemptFailed(rootMessage(ex), properties.maxRetries(), now, properties.initialBackoff(),
                    properties.maxBackoff());
            if (event.getStatus() == OutboxEventStatus.FAILED) {
                log.error("Outbox event {} ({}) parked as FAILED after {} attempts: {}", event.getId(),
                        event.getEventType(), event.getRetryCount(), event.getLastError());
            }
            else {
                log.warn("Outbox event {} ({}) publish attempt {} failed, next attempt at {}: {}", event.getId(),
                        event.getEventType(), event.getRetryCount(), event.getNextAttemptAt(), event.getLastError());
            }
            return ex instanceof AmqpConnectException ? PublishResult.BROKER_UNREACHABLE : PublishResult.FAILED;
        }
    }

    private static Message toMessage(OutboxEventEntity event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(event.getId().toString());
        props.setHeader(HEADER_EVENT_ID, event.getId().toString());
        props.setHeader(HEADER_EVENT_TYPE, event.getEventType());
        props.setHeader(HEADER_AGGREGATE_TYPE, event.getAggregateType());
        props.setHeader(HEADER_AGGREGATE_ID, event.getAggregateId().toString());
        props.setHeader(HEADER_OCCURRED_AT, event.getOccurredAt().toString());
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), props);
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
