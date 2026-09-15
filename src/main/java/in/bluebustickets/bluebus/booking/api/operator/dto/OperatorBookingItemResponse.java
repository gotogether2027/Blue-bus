package in.bluebustickets.bluebus.booking.api.operator.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingItemStatus;

public record OperatorBookingItemResponse(
        UUID bookingItemId,
        UUID passengerId,
        String seatNumber,
        String seatType,
        int originSequence,
        int destinationSequence,
        BookingItemStatus status) {
}
