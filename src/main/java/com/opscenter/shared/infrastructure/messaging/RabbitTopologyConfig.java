package com.opscenter.shared.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology of the platform (04-API §15).
 * <ul>
 *   <li>{@code opscenter.events} - topic exchange every domain event is published to, routing key
 *       such as {@code user.locked} or {@code incident.created}.</li>
 *   <li>{@code opscenter.events.dlx} + {@code opscenter.events.dlq} - dead-letter exchange/queue;
 *       consumer queues of later sprints declare {@code x-dead-letter-exchange} and
 *       {@code x-dead-letter-routing-key} pointing here so poison messages are parked, not lost.</li>
 * </ul>
 * Declared via {@link Declarables} so Spring AMQP's {@code RabbitAdmin} creates everything on the
 * first connection - no manual broker setup for a new developer.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "opscenter.events";
    public static final String DEAD_LETTER_EXCHANGE = "opscenter.events.dlx";
    public static final String DEAD_LETTER_QUEUE = "opscenter.events.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "dead-letter";

    @Bean
    public Declarables opscenterTopology() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
        return new Declarables(events, deadLetterExchange, deadLetterQueue, deadLetterBinding);
    }
}
