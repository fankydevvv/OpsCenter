package com.opscenter.incident;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.opscenter.integration.testsupport.WebhookIntegrationTest;
import com.opscenter.shared.infrastructure.messaging.OutboxRelay;
import com.opscenter.shared.infrastructure.messaging.RabbitTopologyConfig;
import com.opscenter.support.RabbitTestcontainersConfiguration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-OUTBOX-001 for the Sprint 2 events (04-API §15, blueprint §9.1): the events written in the
 * ingestion / command transactions reach RabbitMQ exchange {@code opscenter.events} with the routing
 * keys derived from their names, via the existing outbox relay.
 */
@Import(RabbitTestcontainersConfiguration.class)
class IncidentOutboxIT extends WebhookIntegrationTest {

    private static final String QUEUE = "test.opscenter.sprint2.events";

    @Autowired
    OutboxRelay relay;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Test
    void TC_OUTBOX_001_alertAndIncidentEvents_arePublishedWithTheirRoutingKeys() throws Exception {
        Queue queue = new Queue(QUEUE, true, false, false);
        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(RabbitTopologyConfig.EVENTS_EXCHANGE))
                .with("#"));
        amqpAdmin.purgeQueue(QUEUE, false);

        JsonNode summary = deliver(payload(firing(labels(unique("Relay-"), "odoo-erp", "DEV", "a:1", "critical"),
                null, T0)));
        String incidentId = summary.at("/items/0/incidentId").asString();
        Map<String, Object> ack = new HashMap<>();
        ack.put("version", 0);
        mvc.perform(jsonRequest(post("/api/v1/incidents/" + incidentId + "/acknowledge"), engineerToken(), ack))
                .andExpect(status().isOk());

        while (relay.relayOnce() > 0) {
            // drain every pending event of this context
        }

        Map<String, JsonNode> byRoutingKey = new HashMap<>();
        Message message;
        while ((message = rabbitTemplate.receive(QUEUE, 2_000)) != null) {
            JsonNode payload = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String aggregate = payload.has("incidentId") ? payload.get("incidentId").asString() : null;
            if (incidentId.equals(aggregate) || payload.has("alertId")
                    && summary.at("/items/0/alertId").asString().equals(payload.get("alertId").asString())) {
                byRoutingKey.put(message.getMessageProperties().getReceivedRoutingKey(), payload);
            }
        }

        assertThat(byRoutingKey).containsKeys("alert.received", "incident.created", "incident.acknowledged");
        assertThat(byRoutingKey.get("incident.created").get("severity").asString()).isEqualTo("P1");
        assertThat(byRoutingKey.get("incident.acknowledged").get("fromStatus").asString()).isEqualTo("OPEN");
        assertThat(byRoutingKey.get("incident.acknowledged").get("toStatus").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(byRoutingKey.get("incident.acknowledged").get("actorId").asString()).isEqualTo(ENGINEER_A_ID.toString());
        assertThat(byRoutingKey.get("alert.received").get("mappingStatus").asString()).isEqualTo("MAPPED");
    }
}
