package in.bluebustickets.bluebus.booking.application;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.application.TripCancellationBookingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripCancellationBookingAdapter implements TripCancellationBookingPort {

    private final BookingCancellationService bookingCancellationService;

    public TripCancellationBookingAdapter(BookingCancellationService bookingCancellationService) {
        this.bookingCancellationService = bookingCancellationService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cascadePassengersForLockedTrip(UUID tripId, UUID actorUserId) {
        bookingCancellationService.cascadePassengersForLockedTrip(tripId, actorUserId);
    }
}
