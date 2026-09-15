package in.bluebustickets.bluebus.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;

public record BookingResponse(
        UUID bookingId,
        String bookingReference,
        UUID tripId,
        UUID holdId,
        BookingStatus status,
        int originSequence,
        int destinationSequence,
        UUID originTripStopId,
        UUID destinationTripStopId,
        String currency,
        BigDecimal baseAmount,
        BigDecimal taxAmount,
        BigDecimal feeAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        Instant createdAt,
        List<BookingItemResponse> items,
        List<BookingPassengerResponse> passengers) {
}
