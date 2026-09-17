package in.bluebustickets.bluebus.scheduling.application;

import java.time.Instant;

import in.bluebustickets.bluebus.foundation.api.error.ApplicationConflictException;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;

/**
 * Request-time saleability: SCHEDULED/ON_SALE and inside the persisted booking window,
 * before scheduled departure. Not a stored lifecycle state.
 */
public final class TripSaleability {

    private TripSaleability() {
    }

    public static boolean isSaleableNow(Trip trip, Instant now) {
        if (trip == null) {
            throw new IllegalArgumentException("Trip is required");
        }
        return isSaleableNow(
                trip.getStatus(),
                trip.getBookingOpensAt(),
                trip.getBookingClosesAt(),
                trip.getScheduledDepartureAt(),
                now);
    }

    public static boolean isSaleableNow(
            TripStatus status,
            Instant bookingOpensAt,
            Instant bookingClosesAt,
            Instant scheduledDepartureAt,
            Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }
        if (status != TripStatus.SCHEDULED && status != TripStatus.ON_SALE) {
            return false;
        }
        if (bookingOpensAt == null || bookingClosesAt == null || scheduledDepartureAt == null) {
            return false;
        }
        return !bookingOpensAt.isAfter(now)
                && now.isBefore(bookingClosesAt)
                && now.isBefore(scheduledDepartureAt);
    }

    public static void requireSaleableNow(Trip trip, Instant now) {
        if (isSaleableNow(trip, now)) {
            return;
        }
        throw new ApplicationConflictException(unsaleableMessage(trip, now));
    }

    static String unsaleableMessage(Trip trip, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now instant is required");
        }
        TripStatus status = trip.getStatus();
        if (status != TripStatus.SCHEDULED && status != TripStatus.ON_SALE) {
            return "Trip is not on sale.";
        }
        Instant opens = trip.getBookingOpensAt();
        Instant closes = trip.getBookingClosesAt();
        Instant departure = trip.getScheduledDepartureAt();
        if (opens != null && now.isBefore(opens)) {
            return "Booking is not open.";
        }
        if (closes != null && !now.isBefore(closes)) {
            return "Booking is closed.";
        }
        if (departure != null && !now.isBefore(departure)) {
            return "Trip has departed.";
        }
        return "Trip is not on sale.";
    }
}
