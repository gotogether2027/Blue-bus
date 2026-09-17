package in.bluebustickets.bluebus.ticket.application;

import java.util.UUID;

import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventHandler;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code BOOKING_CONFIRMED} from the transactional outbox and issues a ticket.
 * Loads authoritative booking data from PostgreSQL — never trusts the event payload for
 * passenger/fare/seat snapshots.
 */
@Component
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class BookingConfirmedTicketHandler implements OutboxEventHandler {

    private final TicketApplicationService ticketApplicationService;

    public BookingConfirmedTicketHandler(TicketApplicationService ticketApplicationService) {
        this.ticketApplicationService = ticketApplicationService;
    }

    @Override
    public String eventType() {
        return OutboxProcessorService.BOOKING_CONFIRMED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(OutboxEvent event) {
        UUID bookingId = event.getAggregateId();
        // Null means the booking is no longer CONFIRMED (e.g. trip cancellation); mark published.
        ticketApplicationService.issueForConfirmedBookingInCurrentTransaction(bookingId);
    }
}
