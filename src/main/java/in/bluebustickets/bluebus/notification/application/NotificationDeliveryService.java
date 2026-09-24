package in.bluebustickets.bluebus.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationStatus;
import in.bluebustickets.bluebus.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryProcessor processor;
    private final Map<NotificationChannel, NotificationProvider> providers;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationDeliveryService(
            NotificationRepository notificationRepository,
            NotificationDeliveryProcessor processor,
            List<NotificationProvider> providers,
            NotificationProperties properties,
            Clock clock) {
        this.notificationRepository = notificationRepository;
        this.processor = processor;
        this.providers = new EnumMap<>(NotificationChannel.class);
        for (NotificationProvider provider : providers) {
            this.providers.put(provider.channel(), provider);
        }
        this.properties = properties;
        this.clock = clock;
    }

    public DeliveryResult deliverDue() {
        return deliverDue(clock.instant());
    }

    public DeliveryResult deliverDue(Instant now) {
        int sent = 0;
        int failed = 0;
        int batchSize = properties.getDelivery().getBatchSize();
        List<UUID> dueIds = notificationRepository.findDueDeliveryIds(
                NotificationStatus.PENDING, now, PageRequest.of(0, batchSize));
        for (UUID notificationId : dueIds) {
            try {
                var claimed = processor.tryClaim(notificationId, now);
                if (claimed.isEmpty()) {
                    continue;
                }
                Notification notification = claimed.get();
                NotificationProvider provider = providers.get(notification.getChannel());
                if (provider == null) {
                    processor.recordFailure(
                            notificationId,
                            clock.instant(),
                            false,
                            "PROVIDER_MISSING",
                            "No provider registered for " + notification.getChannel());
                    failed++;
                    continue;
                }
                NotificationProvider.ProviderResult result = provider.send(notification);
                switch (result.outcome()) {
                    case SUCCESS -> {
                        processor.markSent(notificationId, result.providerMessageId(), clock.instant());
                        sent++;
                        log.info(
                                "Notification delivery success notificationId={} eventType={} channel={}",
                                notificationId,
                                notification.getEventType(),
                                notification.getChannel());
                    }
                    case RETRYABLE_FAILURE -> {
                        processor.recordFailure(
                                notificationId,
                                clock.instant(),
                                true,
                                result.failureCode(),
                                result.failureMessage());
                        failed++;
                        log.warn(
                                "Notification delivery retryable notificationId={} eventType={} channel={} code={}",
                                notificationId,
                                notification.getEventType(),
                                notification.getChannel(),
                                result.failureCode());
                    }
                    case NON_RETRYABLE_FAILURE -> {
                        processor.recordFailure(
                                notificationId,
                                clock.instant(),
                                false,
                                result.failureCode(),
                                result.failureMessage());
                        failed++;
                        log.warn(
                                "Notification delivery failed notificationId={} eventType={} channel={} code={}",
                                notificationId,
                                notification.getEventType(),
                                notification.getChannel(),
                                result.failureCode());
                    }
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn(
                        "Notification delivery failure notificationId={}: {}",
                        notificationId,
                        exception.getMessage());
                try {
                    processor.recordFailure(
                            notificationId,
                            clock.instant(),
                            true,
                            "DELIVERY_ERROR",
                            exception.getMessage());
                } catch (RuntimeException recordFailure) {
                    log.warn(
                            "Failed to record notification delivery failure for {}: {}",
                            notificationId,
                            recordFailure.getMessage());
                }
            }
        }
        return new DeliveryResult(sent, failed);
    }

    public record DeliveryResult(int sent, int failed) {
    }
}
