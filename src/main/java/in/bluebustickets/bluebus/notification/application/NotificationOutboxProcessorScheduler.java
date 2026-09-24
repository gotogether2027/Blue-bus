package in.bluebustickets.bluebus.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.notifications.processor", name = "enabled", matchIfMissing = true)
public class NotificationOutboxProcessorScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxProcessorScheduler.class);

    private final NotificationOutboxProcessorService processorService;

    public NotificationOutboxProcessorScheduler(NotificationOutboxProcessorService processorService) {
        this.processorService = processorService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.notifications.processor.poll-interval-ms:5000}")
    public void processDue() {
        try {
            processorService.processPending();
        } catch (RuntimeException exception) {
            log.warn("Notification processor pass failed: {}", exception.getMessage());
        }
    }
}
