package in.bluebustickets.bluebus.booking.domain;

/**
 * Booking purchase lifecycle. Payment confirmation to {@link #CONFIRMED} is deferred.
 */
public enum BookingStatus {
    INITIATED,
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED
}
