package in.bluebustickets.bluebus.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingCancellationStatus;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;

public record BookingCancellationResponse(
        UUID cancellationId,
        UUID bookingId,
        BookingStatus previousStatus,
        BookingCancellationStatus status,
        String reason,
        String policyCode,
        BigDecimal refundableAmount,
        String currency,
        Instant cancelledAt,
        BookingResponse booking) {
}
