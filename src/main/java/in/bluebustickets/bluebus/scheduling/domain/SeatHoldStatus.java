package in.bluebustickets.bluebus.scheduling.domain;

/**
 * Lifecycle of a temporary multi-seat segment hold. Not a booking.
 */
public enum SeatHoldStatus {
    ACTIVE,
    CONSUMED,
    EXPIRED,
    CANCELLED
}
