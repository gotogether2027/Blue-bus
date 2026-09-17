package in.bluebustickets.bluebus.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.ticket.domain.TicketStatus;

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
        Instant paymentExpiresAt,
        List<BookingItemResponse> items,
        List<BookingPassengerResponse> passengers,
        BookingTripResponse trip,
        UUID paymentAttemptId,
        PaymentStatus paymentStatus,
        UUID ticketId,
        String ticketNumber,
        TicketStatus ticketStatus,
        RefundStatus latestRefundStatus,
        BigDecimal latestRefundAmount) {
}
