package in.bluebustickets.bluebus.scheduling.application;

import java.util.UUID;

/**
 * Booking-owned read used while a trip row is locked for operator/admin cancellation.
 */
public interface TripCancellationBookingPort {

    boolean existsConfirmedOrRefundPending(UUID tripId);
}
