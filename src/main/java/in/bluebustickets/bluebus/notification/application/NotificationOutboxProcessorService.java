package in.bluebustickets.bluebus.notification.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.rabbit.RabbitMqProperties;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Local notification consumer. Skips only when the RabbitMQ notification consumer
 * is authoritative ({@code rabbitmq.enabled} and {@code notification-consumer-enabled}).
 * RabbitMQ enabled with the notification consumer disabled stays on this processor.
 * Does not touch {@code published_at} or {@code rabbit_published_at}.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationOutboxProcessorService {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxProcessorService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final NotificationApplicationService notificationApplicationService;
    private final NotificationProperties properties;
    private final RabbitMqProperties rabbitMqProperties;
    private final Clock clock;

    public NotificationOutboxProcessorService(
            OutboxEventRepository outboxEventRepository,
            NotificationApplicationService notificationApplicationService,
            NotificationProperties properties,
            RabbitMqProperties rabbitMqProperties,
            Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.notificationApplicationService = notificationApplicationService;
        this.properties = properties;
        this.rabbitMqProperties = rabbitMqProperties;
        this.clock = clock;
    }

    public Result processPending() {
        if (rabbitMqProperties.isNotificationConsumerAuthoritative()) {
            log.debug("Skipping local notification processing; RabbitMQ consumer is authoritative");
            return new Result(0, 0);
        }
        int processed = 0;
        int failed = 0;
        List<UUID> dueIds = outboxEventRepository.findUnprocessedNotificationIds(
                NotificationEventType.SOURCE_OUTBOX_TYPES,
                NotificationApplicationService.CONSUMER_NAME,
                PageRequest.of(0, properties.getProcessor().getBatchSize()));
        for (UUID eventId : dueIds) {
            try {
                OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
                if (event == null) {
                    continue;
                }
                notificationApplicationService.processOutboxEvent(event);
                processed++;
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Notification outbox processing failed eventId={}: {}", eventId, exception.getMessage());
            }
        }
        if (processed > 0 || failed > 0) {
            log.info("Notification processor pass complete: processed={}, failures={}", processed, failed);
        }
        return new Result(processed, failed);
    }

    public record Result(int processed, int failures) {
    }
}
