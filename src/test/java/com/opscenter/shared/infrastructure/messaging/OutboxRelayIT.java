package com.opscenter.shared.infrastructure.messaging;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.infrastructure.persistence.OutboxEventEntity;
import com.opscenter.shared.infrastructure.persistence.OutboxEventRepository;
import com.opscenter.shared.infrastructure.persistence.OutboxEventStatus;
import com.opscenter.support.AbstractIntegrationTest;
import com.opscenter.support.RabbitTestcontainersConfiguration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TC-OUTBOX-001 / TC-OUTBOX-002 with a real RabbitMQ 4 container: an event appended in a business
 * transaction is relayed to {@code opscenter.events}; when the broker is unreachable the event
 * stays {@code PENDING} with a retry counter and exponential backoff (D-28), is published once the
 * broker is back and the backoff elapsed, and is parked as {@code FAILED} only after
 * {@code max-retries}.
 */
@Import(RabbitTestcontainersConfiguration.class)
class OutboxRelayIT extends AbstractIntegrationTest {

    private static final String TEST_QUEUE = "test.opscenter.events.all";

    @Autowired
    OutboxAppender appender;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository repository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxProperties properties;

    @Autowired
    Clock clock;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    JdbcTemplate jdbc;

    private CachingConnectionFactory deadFactory;

    @BeforeEach
    void declareTestQueue() throws Exception {
        // RabbitMQ 4 refuses transient non-exclusive queues (deprecated feature), so declare it durable.
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        amqpAdmin.declareQueue(queue);
        Binding binding = BindingBuilder.bind(queue).to(new TopicExchange(RabbitTopologyConfig.EVENTS_EXCHANGE)).with("#");
        amqpAdmin.declareBinding(binding);
        amqpAdmin.purgeQueue(TEST_QUEUE, false);
        repository.deleteAll();

        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        deadFactory = new CachingConnectionFactory("127.0.0.1", unusedPort);
        deadFactory.setConnectionTimeout(2000);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        deadFactory.destroy();
    }

    /** A relay whose broker does not exist; {@code maxRetries} 3 and the given backoff. */
    private OutboxRelay brokenRelay(Duration initialBackoff) {
        return new OutboxRelay(repository, new RabbitTemplate(deadFactory), tx,
                new OutboxProperties(false, Duration.ofSeconds(2), 50, 3, initialBackoff, Duration.ofMinutes(5)), clock);
    }

    /**
     * Pretends the backoff elapsed: what the scheduler would see a little later.
     * Uses the JVM clock (the one OutboxRelay compares against), not the database's now():
     * the Testcontainers VM clock can drift from the host clock and make the row "not due yet".
     */
    private void makeDue(UUID eventId) {
        jdbc.update("update outbox_events set next_attempt_at = ? where id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), eventId);
    }

    @Test
    void topologyIsDeclaredOnFirstConnection() {
        assertThat(amqpAdmin.getQueueInfo(RabbitTopologyConfig.DEAD_LETTER_QUEUE)).isNotNull();
    }

    @Test
    void appendOutsideTransaction_isRejected() {
        assertThatThrownBy(() -> appender.append("User", UUID.randomUUID(), "UserLocked", Map.of("x", 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void TC_OUTBOX_001_eventAppendedInTransaction_isPublishedWithRoutingKeyAndHeaders() {
        UUID userId = UUID.randomUUID();
        OutboxEventEntity appended = tx.execute(s -> appender.append("User", userId, "UserLocked",
                Map.of("userId", userId.toString(), "reason", "policy")));
        assertThat(repository.findById(appended.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(1);
        OutboxEventEntity row = repository.findById(appended.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();

        Message message = rabbitTemplate.receive(TEST_QUEUE, 10_000);
        assertThat(message).as("message on queue bound with #").isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("user.locked");
        assertThat(message.getMessageProperties().getHeader(OutboxRelay.HEADER_EVENT_TYPE).toString()).isEqualTo("UserLocked");
        assertThat(message.getMessageProperties().getHeader(OutboxRelay.HEADER_AGGREGATE_ID).toString()).isEqualTo(userId.toString());
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(appended.getId().toString());
        // jsonb returns a canonical form (spacing/key order may differ), so compare the parsed payload
        JsonNode payload = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(payload.get("reason").asString()).isEqualTo("policy");
        assertThat(payload.get("userId").asString()).isEqualTo(userId.toString());
    }

    @Test
    void TC_OUTBOX_002_brokerUnavailable_keepsEventPendingWithBackoff_thenPublishesWhenBackAndDue() {
        UUID userId = UUID.randomUUID();
        OutboxEventEntity appended = tx.execute(s -> appender.append("User", userId, "UserLocked", Map.of("userId", userId.toString())));
        OutboxRelay broken = brokenRelay(Duration.ofSeconds(30));

        assertThat(broken.relayOnce()).isZero();
        OutboxEventEntity afterFirst = repository.findById(appended.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFirst.getRetryCount()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).isNotBlank();
        assertThat(afterFirst.getNextAttemptAt()).as("D-28 backoff scheduled")
                .isAfter(clock.instant().plus(Duration.ofSeconds(25)));

        // broker "back", but the row is not due yet: the healthy relay leaves it alone
        assertThat(relay.relayOnce()).isZero();
        assertThat(repository.findById(appended.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // ...and publishes it once the backoff elapsed
        makeDue(appended.getId());
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(repository.findById(appended.getId()).orElseThrow().getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(rabbitTemplate.receive(TEST_QUEUE, 10_000)).isNotNull();
    }

    @Test
    void TC_OUTBOX_002_rowThatKeepsFailing_isParkedAsFailedAfterMaxRetries_andNotPolledAgain() {
        UUID userId = UUID.randomUUID();
        OutboxEventEntity doomed = tx.execute(s -> appender.append("User", userId, "UserUnlocked", Map.of()));
        OutboxRelay broken = brokenRelay(Duration.ZERO);

        for (int i = 0; i < 3; i++) {
            broken.relayOnce();
        }

        OutboxEventEntity parked = repository.findById(doomed.getId()).orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(parked.getRetryCount()).isEqualTo(3);
        assertThat(relay.relayOnce()).as("FAILED rows are not retried automatically").isZero();
    }

    /** D-28: one unreachable broker costs one connection timeout per round, not one per event. */
    @Test
    void brokerUnreachable_stopsTheBatchAfterTheFirstEvent() {
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            tx.execute(s -> appender.append("User", id, "UserLocked", Map.of("n", 1)));
        }

        assertThat(brokenRelay(Duration.ZERO).relayOnce()).isZero();

        List<Integer> retries = jdbc.queryForList("select retry_count from outbox_events order by retry_count", Integer.class);
        assertThat(retries).containsExactly(0, 0, 1);
    }
}
