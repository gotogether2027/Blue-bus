package in.bluebustickets.bluebus.booking.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;

/**
 * Narrow booking-owned boundary used by Payments. Callers must provide a transaction; this port
 * locks and changes Booking state but never accepts client-controlled money.
 */
public interface BookingPaymentPort {

    PaymentBookingSnapshot lockOwnedForPaymentInitiation(UUID bookingId, UUID userId, Instant now);

    PaymentBookingSnapshot lockForPaymentOutcome(UUID bookingId);

    BookingStatus confirmLockedPendingPayment(UUID bookingId);

    record PaymentBookingSnapshot(
            UUID bookingId,
            UUID userId,
            BookingStatus status,
            BigDecimal totalAmount,
            String currency,
            Instant paymentExpiresAt) {
    }
}
