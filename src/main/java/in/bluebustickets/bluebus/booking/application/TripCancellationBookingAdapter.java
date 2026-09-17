package in.bluebustickets.bluebus.booking.application;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.scheduling.application.TripCancellationBookingPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripCancellationBookingAdapter implements TripCancellationBookingPort {

    private static final List<BookingStatus> BLOCKING_STATUSES = List.of(
            BookingStatus.CONFIRMED,
            BookingStatus.REFUND_PENDING);

    private final BookingRepository bookingRepository;

    public TripCancellationBookingAdapter(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public boolean existsConfirmedOrRefundPending(UUID tripId) {
        if (tripId == null) {
            throw new IllegalArgumentException("tripId is required");
        }
        return bookingRepository.existsByTripIdAndStatusIn(tripId, BLOCKING_STATUSES);
    }
}
