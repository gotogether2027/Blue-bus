package in.bluebustickets.bluebus.scheduling.application;

import java.util.UUID;

/**
 * Booking-owned passenger cascade used while a trip row is already locked for
 * operator/admin cancellation. Must not lock the trip again or call a payment provider.
 */
public interface TripCancellationBookingPort {

    void cascadePassengersForLockedTrip(UUID tripId, UUID actorUserId);
}
