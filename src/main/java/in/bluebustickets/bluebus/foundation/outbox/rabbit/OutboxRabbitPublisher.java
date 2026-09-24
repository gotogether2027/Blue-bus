package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes one outbox envelope and waits for a publisher confirm.
 * Must be called outside any booking/payment transaction.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
public class OutboxRabbitPublisher {

    static final String HEADER_EVENT_TYPE = "eventType";
    static final String HEADER_SCHEMA_VERSION = "schemaVersion";
    static final String HEADER_CORRELATION_ID = "correlationId";
    static final String HEADER_CAUSATION_ID = "causationId";
    static final String HEADER_AGGREGATE_TYPE = "aggregateType";
    static final String HEADER_AGGREGATE_ID = "aggregateId";

    private static final Logger log = LoggerFactory.getLogger(OutboxRabbitPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMqProperties properties;
    private final ObjectMapper objectMapper;

    public OutboxRabbitPublisher(
            RabbitTemplate rabbitTemplate,
            RabbitMqProperties properties,
            ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void publish(OutboxEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("outbox event is required");
        }
        log.info(
                "RabbitMQ publish attempt eventId={} eventType={} aggregateType={} aggregateId={}",
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId());
        try {
            Message message = toMessage(event);
            CorrelationData correlation = new CorrelationData(event.getId().toString());
            rabbitTemplate.send(
                    properties.getExchange(),
                    properties.routingKeyFor(event.getEventType()),
                    message,
                    correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getPublisherConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new OutboxRabbitPublishException(
                        "RabbitMQ nack for event " + event.getId()
                                + (confirm == null || confirm.getReason() == null ? "" : ": " + confirm.getReason()));
            }
            if (correlation.getReturned() != null) {
                throw new OutboxRabbitPublishException(
                        "RabbitMQ returned unroutable event " + event.getId());
            }
            log.info(
                    "RabbitMQ publish success eventId={} eventType={}",
                    event.getId(),
                    event.getEventType());
        } catch (OutboxRabbitPublishException exception) {
            log.warn(
                    "RabbitMQ publish failure eventId={} eventType={}: {}",
                    event.getId(),
                    event.getEventType(),
                    exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            log.warn(
                    "RabbitMQ publish failure eventId={} eventType={}: {}",
                    event.getId(),
                    event.getEventType(),
                    exception.getMessage());
            throw new OutboxRabbitPublishException(
                    "RabbitMQ publish failed for event " + event.getId(),
                    exception);
        }
    }

    public OutboxMessageEnvelope envelopeOf(OutboxEvent event) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.getPayloadJson());
        } catch (JsonProcessingException exception) {
            payload = objectMapper.getNodeFactory().textNode(event.getPayloadJson());
        }
        return new OutboxMessageEnvelope(
                event.getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getSchemaVersion(),
                event.getCorrelationId(),
                event.getCausationId(),
                event.getOccurredAt(),
                payload);
    }

    Message toMessage(OutboxEvent event) {
        OutboxMessageEnvelope envelope = envelopeOf(event);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException exception) {
            throw new OutboxRabbitPublishException(
                    "Failed to serialize outbox envelope " + event.getId(), exception);
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.getId().toString());
        properties.setType(event.getEventType());
        properties.setHeader(HEADER_EVENT_TYPE, event.getEventType());
        properties.setHeader(HEADER_SCHEMA_VERSION, event.getSchemaVersion());
        properties.setHeader(HEADER_AGGREGATE_TYPE, event.getAggregateType());
        properties.setHeader(HEADER_AGGREGATE_ID, event.getAggregateId().toString());
        if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
            properties.setCorrelationId(event.getCorrelationId());
            properties.setHeader(HEADER_CORRELATION_ID, event.getCorrelationId());
        }
        if (event.getCausationId() != null && !event.getCausationId().isBlank()) {
            properties.setHeader(HEADER_CAUSATION_ID, event.getCausationId());
        }
        return new Message(body, properties);
    }
}
