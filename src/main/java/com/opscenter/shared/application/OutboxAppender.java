package com.opscenter.shared.application;

import java.time.Clock;
import java.util.UUID;

import com.opscenter.shared.infrastructure.persistence.OutboxEventEntity;
import com.opscenter.shared.infrastructure.persistence.OutboxEventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a domain event into {@code outbox_events} <b>inside the caller's transaction</b>
 * (transactional outbox, 03-DB §23 / §27, 04-API §15).
 * <p>
 * {@code Propagation.MANDATORY} is the whole point: appending outside a business transaction would
 * allow an event to exist without its state change (or vice versa), so the call fails loudly
 * instead. Publishing to RabbitMQ is done later by {@code OutboxRelay}; the broker being down
 * never breaks a business operation (TC-OUTBOX-002).
 */
@Service
public class OutboxAppender {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxAppender(OutboxEventRepository repository, JsonMapper jsonMapper, Clock clock) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @param aggregateType e.g. {@code User}
     * @param aggregateId   id of the aggregate the event is about
     * @param eventType     UpperCamelCase event name, e.g. {@code UserLocked}; the routing key
     *                      {@code user.locked} is derived from it
     * @param payload       any JSON-serialisable object; must not contain secrets
     * @return the persisted outbox row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String json = jsonMapper.writeValueAsString(payload);
        OutboxEventEntity event = new OutboxEventEntity(UUID.randomUUID(), aggregateType, aggregateId,
                eventType, json, clock.instant());
        return repository.save(event);
    }
}
