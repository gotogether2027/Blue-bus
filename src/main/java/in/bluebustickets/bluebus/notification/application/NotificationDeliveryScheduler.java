package in.bluebustickets.bluebus.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.notifications.delivery", name = "enabled", matchIfMissing = true)
public class NotificationDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryScheduler.class);

    private final NotificationDeliveryService deliveryService;

    public NotificationDeliveryScheduler(NotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.notifications.delivery.poll-interval-ms:5000}")
    public void deliverDue() {
        try {
            deliveryService.deliverDue();
        } catch (RuntimeException exception) {
            log.warn("Notification delivery pass failed: {}", exception.getMessage());
        }
    }
}
