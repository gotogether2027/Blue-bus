package in.bluebustickets.bluebus.ticket.api.dto;

import java.math.BigDecimal;

public record TicketPassengerResponse(
        String name,
        Integer age,
        String gender,
        String seat,
        BigDecimal fareAmount,
        String currency) {
}
