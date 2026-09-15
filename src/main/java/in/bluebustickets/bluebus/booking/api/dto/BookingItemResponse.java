package in.bluebustickets.bluebus.booking.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingItemStatus;

public record BookingItemResponse(
        UUID bookingItemId,
        UUID seatInventoryId,
        UUID passengerId,
        String seatNumber,
        String seatType,
        int originSequence,
        int destinationSequence,
        BigDecimal baseAmount,
        BigDecimal totalAmount,
        BookingItemStatus status) {
}
