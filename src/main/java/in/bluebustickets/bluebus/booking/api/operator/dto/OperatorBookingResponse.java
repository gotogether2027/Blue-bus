package in.bluebustickets.bluebus.booking.api.operator.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.api.dto.BookingTripResponse;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;

public record OperatorBookingResponse(
        UUID bookingId,
        String bookingReference,
        BookingStatus status,
        UUID tripId,
        int originSequence,
        int destinationSequence,
        UUID originTripStopId,
        UUID destinationTripStopId,
        String currency,
        BigDecimal totalAmount,
        Instant createdAt,
        List<OperatorBookingItemResponse> items,
        List<OperatorBookingPassengerResponse> passengers,
        BookingTripResponse trip) {
}
