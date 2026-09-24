package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Discovers unpublished broker events, claims them, publishes outside the
 * business transaction, and marks {@code rabbit_published_at} only after confirm.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
public class OutboxRabbitPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRabbitPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRabbitPublisherProcessor processor;
    private final OutboxRabbitPublisher publisher;
    private final RabbitMqProperties properties;
    private final Clock clock;

    public OutboxRabbitPublisherService(
            OutboxEventRepository outboxEventRepository,
            OutboxRabbitPublisherProcessor processor,
            OutboxRabbitPublisher publisher,
            RabbitMqProperties properties,
            Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.processor = processor;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    public OutboxRabbitPublishResult publishPendingBookingConfirmed() {
        return publishPending(List.of(OutboxProcessorService.BOOKING_CONFIRMED), clock.instant());
    }

    public OutboxRabbitPublishResult publishPendingBookingConfirmed(Instant now) {
        return publishPending(List.of(OutboxProcessorService.BOOKING_CONFIRMED), now);
    }

    public OutboxRabbitPublishResult publishPending() {
        return publishPending(NotificationEventType.SOURCE_OUTBOX_TYPES, clock.instant());
    }

    public OutboxRabbitPublishResult publishPending(java.util.Collection<String> eventTypes, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }

        int published = 0;
        int failed = 0;
        int batchSize = properties.getPublisher().getBatchSize();

        while (true) {
            List<UUID> dueIds = outboxEventRepository.findUnpublishedRabbitIdsByEventTypes(
                    eventTypes,
                    now,
                    PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            int publishedInBatch = 0;
            int claimedInBatch = 0;
            for (UUID eventId : dueIds) {
                try {
                    var claimed = processor.tryClaim(eventId, now);
                    if (claimed.isEmpty()) {
                        continue;
                    }
                    claimedInBatch++;
                    OutboxEvent event = claimed.get();
                    log.info(
                            "RabbitMQ event selected eventId={} eventType={} rabbitAttemptCount={}",
                            event.getId(),
                            event.getEventType(),
                            event.getRabbitAttemptCount());
                    publisher.publish(event);
                    processor.markRabbitPublished(event.getId(), clock.instant());
                    published++;
                    publishedInBatch++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn(
                            "RabbitMQ publish failure eventId={}: {}",
                            eventId,
                            exception.getMessage());
                    try {
                        processor.scheduleBackoff(eventId, clock.instant());
                    } catch (RuntimeException backoffFailure) {
                        log.warn(
                                "Failed to schedule RabbitMQ publish backoff for {}: {}",
                                eventId,
                                backoffFailure.getMessage());
                    }
                }
            }

            if (dueIds.size() < batchSize || claimedInBatch == 0) {
                break;
            }
            now = clock.instant();
            if (publishedInBatch == 0 && failed > 0) {
                break;
            }
        }

        if (published > 0 || failed > 0) {
            log.info(
                    "RabbitMQ publisher pass complete: published={}, failed={}",
                    published,
                    failed);
        }
        return new OutboxRabbitPublishResult(published, failed);
    }

    public record OutboxRabbitPublishResult(int published, int failed) {
    }
}
