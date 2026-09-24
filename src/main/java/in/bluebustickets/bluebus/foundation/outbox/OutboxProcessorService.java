package in.bluebustickets.bluebus.foundation.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.rabbit.RabbitMqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Local database outbox consumer for Phase 9.4B. Claims unpublished supported events in
 * bounded batches using PostgreSQL row locks.
 * <p>
 * {@code published_at} is local-handler completion only. RabbitMQ delivery uses
 * {@code rabbit_published_at}. When the RabbitMQ BOOKING_CONFIRMED consumer is
 * enabled, this processor skips that event type so the two paths do not race.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.outbox", name = "enabled", matchIfMissing = true)
public class OutboxProcessorService {

    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessorService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventProcessor outboxEventProcessor;
    private final OutboxProcessorProperties properties;
    private final Clock clock;
    private final RabbitMqProperties rabbitMqProperties;

    public OutboxProcessorService(
            OutboxEventRepository outboxEventRepository,
            OutboxEventProcessor outboxEventProcessor,
            OutboxProcessorProperties properties,
            Clock clock,
            RabbitMqProperties rabbitMqProperties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventProcessor = outboxEventProcessor;
        this.properties = properties;
        this.clock = clock;
        this.rabbitMqProperties = rabbitMqProperties;
    }

    public OutboxProcessingResult processPendingBookingConfirmed() {
        return processPendingBookingConfirmed(clock.instant());
    }

    public OutboxProcessingResult processPendingBookingConfirmed(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }

        if (rabbitMqProperties.isBookingConfirmedConsumerAuthoritative()) {
            log.debug(
                    "Skipping local BOOKING_CONFIRMED processing; RabbitMQ consumer is authoritative");
            return OutboxProcessingResult.empty();
        }

        OutboxProcessingResult total = OutboxProcessingResult.empty();
        int batchSize = properties.getBatchSize();

        while (true) {
            List<UUID> dueIds = outboxEventRepository.findUnpublishedIdsByEventType(
                    BOOKING_CONFIRMED,
                    PageRequest.of(0, batchSize));
            if (dueIds.isEmpty()) {
                break;
            }

            int processedInBatch = 0;
            int failuresInBatch = 0;
            for (UUID eventId : dueIds) {
                try {
                    var processed = outboxEventProcessor.tryProcess(eventId, now);
                    if (processed.isPresent()) {
                        processedInBatch++;
                    }
                } catch (RuntimeException exception) {
                    failuresInBatch++;
                    try {
                        outboxEventProcessor.recordFailedAttempt(eventId);
                    } catch (RuntimeException recordFailure) {
                        log.warn(
                                "Failed to record outbox attempt for {}: {}",
                                eventId,
                                recordFailure.getMessage());
                    }
                    log.warn(
                            "Failed to process outbox event {}: {}",
                            eventId,
                            exception.getMessage());
                }
            }

            total = total.plus(new OutboxProcessingResult(processedInBatch, failuresInBatch));
            if (dueIds.size() < batchSize || processedInBatch == 0) {
                break;
            }
        }

        if (total.processed() > 0 || total.failures() > 0) {
            log.info(
                    "Outbox processor pass complete: processed={}, failures={}",
                    total.processed(),
                    total.failures());
        }
        return total;
    }
}
