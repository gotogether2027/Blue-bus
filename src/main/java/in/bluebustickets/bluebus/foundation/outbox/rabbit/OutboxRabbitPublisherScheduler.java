package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disabled unless RabbitMQ and the publisher scheduler are both enabled.
 * Tests typically set {@code blue-bus.rabbitmq.publisher.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq.publisher", name = "enabled", matchIfMissing = true)
public class OutboxRabbitPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRabbitPublisherScheduler.class);

    private final OutboxRabbitPublisherService publisherService;

    public OutboxRabbitPublisherScheduler(OutboxRabbitPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.rabbitmq.publisher.poll-interval-ms:5000}")
    public void publishDueEvents() {
        try {
            publisherService.publishPendingBookingConfirmed();
        } catch (RuntimeException exception) {
            log.warn("RabbitMQ publisher pass failed: {}", exception.getMessage());
        }
    }
}
