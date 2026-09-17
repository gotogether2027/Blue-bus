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

    /**
     * Non-locking status read for refund eligibility checks outside a payment outcome transaction.
     */
    BookingStatus currentStatus(UUID bookingId);

    BookingStatus confirmLockedPendingPayment(UUID bookingId);

    /**
     * Transitions {@code REFUND_PENDING → REFUNDED} when a refund is authoritatively confirmed.
     * Already-{@code REFUNDED} is a no-op. Other statuses are left unchanged (no downgrade).
     */
    BookingStatus markRefunded(UUID bookingId);

    record PaymentBookingSnapshot(
            UUID bookingId,
            UUID userId,
            BookingStatus status,
            BigDecimal totalAmount,
            String currency,
            Instant paymentExpiresAt) {
    }
}
