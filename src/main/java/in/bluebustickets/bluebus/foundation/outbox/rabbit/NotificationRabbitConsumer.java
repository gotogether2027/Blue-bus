package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.notification.application.NotificationApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent notification consumer. Creates notification rows from the existing
 * outbox envelope. Duplicate deliveries are absorbed by {@code processed_events}
 * and notification unique indexes.
 */
@Service
@ConditionalOnBean(NotificationApplicationService.class)
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "notification-consumer-enabled", matchIfMissing = true)
public class NotificationRabbitConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationRabbitConsumer.class);

    private final NotificationApplicationService notificationApplicationService;

    public NotificationRabbitConsumer(NotificationApplicationService notificationApplicationService) {
        this.notificationApplicationService = notificationApplicationService;
    }

    @Transactional
    public NotificationApplicationService.Outcome process(OutboxMessageEnvelope envelope) {
        BookingConfirmedRabbitConsumer.validate(envelope);
        log.info(
                "RabbitMQ notification consumer received eventId={} eventType={} aggregateId={}",
                envelope.eventId(),
                envelope.eventType(),
                envelope.aggregateId());
        String payloadJson = envelope.payload() == null ? "{}" : envelope.payload().toString();
        OutboxEvent event = new OutboxEvent(
                envelope.eventId(),
                envelope.eventType(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                payloadJson,
                envelope.occurredAt(),
                envelope.correlationId(),
                envelope.causationId());
        return notificationApplicationService.processOutboxEvent(event);
    }
}
