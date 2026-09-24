package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import in.bluebustickets.bluebus.ticket.application.TicketApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent BOOKING_CONFIRMED consumer. Ticket issuance reuses the existing
 * {@link TicketApplicationService} path. Duplicate deliveries are absorbed by
 * {@code processed_events} and {@code uq_tickets_booking}.
 */
@Service
@ConditionalOnBean(TicketApplicationService.class)
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "blue-bus.rabbitmq", name = "consumer-enabled", matchIfMissing = true)
public class BookingConfirmedRabbitConsumer {

    public enum Outcome {
        PROCESSED,
        DUPLICATE,
        UNKNOWN_EVENT_TYPE
    }

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedRabbitConsumer.class);

    private final ProcessedEventRepository processedEventRepository;
    private final TicketApplicationService ticketApplicationService;
    private final Clock clock;

    public BookingConfirmedRabbitConsumer(
            ProcessedEventRepository processedEventRepository,
            TicketApplicationService ticketApplicationService,
            Clock clock) {
        this.processedEventRepository = processedEventRepository;
        this.ticketApplicationService = ticketApplicationService;
        this.clock = clock;
    }

    @Transactional
    public Outcome process(OutboxMessageEnvelope envelope) {
        validate(envelope);
        UUID eventId = envelope.eventId();
        String eventType = envelope.eventType();
        log.info(
                "RabbitMQ consumer received eventId={} eventType={} aggregateId={}",
                eventId,
                eventType,
                envelope.aggregateId());

        if (!OutboxProcessorService.BOOKING_CONFIRMED.equals(eventType)) {
            log.info(
                    "RabbitMQ consumer unknown event type eventId={} eventType={}",
                    eventId,
                    eventType);
            return Outcome.UNKNOWN_EVENT_TYPE;
        }

        Instant now = clock.instant();
        int inserted = processedEventRepository.tryInsert(
                eventId,
                RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER,
                now);
        if (inserted == 0) {
            log.info(
                    "RabbitMQ consumer duplicate eventId={} eventType={}",
                    eventId,
                    eventType);
            return Outcome.DUPLICATE;
        }

        ticketApplicationService.issueForConfirmedBookingInCurrentTransaction(envelope.aggregateId());
        log.info(
                "RabbitMQ consumer success eventId={} eventType={} aggregateId={}",
                eventId,
                eventType,
                envelope.aggregateId());
        return Outcome.PROCESSED;
    }

    static OutboxMessageEnvelope validate(OutboxMessageEnvelope envelope) {
        if (envelope == null) {
            throw new MalformedOutboxMessageException("outbox envelope is required");
        }
        if (envelope.eventId() == null) {
            throw new MalformedOutboxMessageException("eventId is required");
        }
        if (envelope.eventType() == null || envelope.eventType().isBlank()) {
            throw new MalformedOutboxMessageException("eventType is required");
        }
        if (envelope.aggregateType() == null || envelope.aggregateType().isBlank()) {
            throw new MalformedOutboxMessageException("aggregateType is required");
        }
        if (envelope.aggregateId() == null) {
            throw new MalformedOutboxMessageException("aggregateId is required");
        }
        if (envelope.schemaVersion() < 1) {
            throw new MalformedOutboxMessageException("schemaVersion must be >= 1");
        }
        if (envelope.occurredAt() == null) {
            throw new MalformedOutboxMessageException("occurredAt is required");
        }
        return envelope;
    }
}
