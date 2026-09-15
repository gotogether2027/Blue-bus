package in.bluebustickets.bluebus.booking.application;

/**
 * Outcome of one reaper pass over due PENDING_PAYMENT bookings.
 */
public record BookingExpiryResult(int bookingsExpired, int allocationsReleased) {

    public static BookingExpiryResult empty() {
        return new BookingExpiryResult(0, 0);
    }

    public BookingExpiryResult plus(BookingExpiryResult other) {
        return new BookingExpiryResult(
                bookingsExpired + other.bookingsExpired(),
                allocationsReleased + other.allocationsReleased());
    }
}
