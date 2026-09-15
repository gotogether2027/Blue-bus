package in.bluebustickets.bluebus.ticket.api.dto;

import java.time.Instant;

public record TicketJourneyResponse(
        String origin,
        String destination,
        Instant departure,
        Instant arrival) {
}
