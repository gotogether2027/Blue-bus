package in.bluebustickets.bluebus.ticket.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.ticket.domain.TicketStatus;

public record TicketResponse(
        UUID ticketId,
        String ticketNumber,
        TicketStatus status,
        Instant issuedAt,
        String bookingReference,
        UUID bookingId,
        TicketOperatorResponse operator,
        TicketJourneyResponse journey,
        List<TicketPassengerResponse> passengers,
        BigDecimal amount,
        String currency) {
}
