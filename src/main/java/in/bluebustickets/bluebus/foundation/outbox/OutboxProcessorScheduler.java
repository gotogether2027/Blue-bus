package in.bluebustickets.bluebus.foundation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Disabled in tests via {@code blue-bus.outbox.processor.enabled=false}.
 * Outbox processor stack itself is gated by {@code blue-bus.outbox.enabled}.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.outbox", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.outbox.processor", name = "enabled", matchIfMissing = true)
public class OutboxProcessorScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessorScheduler.class);

    private final OutboxProcessorService outboxProcessorService;

    public OutboxProcessorScheduler(OutboxProcessorService outboxProcessorService) {
        this.outboxProcessorService = outboxProcessorService;
    }

    @Scheduled(fixedDelayString = "${blue-bus.outbox.processor.poll-interval-ms:5000}")
    public void processDueEvents() {
        try {
            outboxProcessorService.processPendingBookingConfirmed();
        } catch (RuntimeException exception) {
            log.warn("Outbox processor pass failed: {}", exception.getMessage());
        }
    }
}
