package in.bluebustickets.bluebus.booking.repository;

import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.booking.domain.BookingCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingCancellationRepository extends JpaRepository<BookingCancellation, UUID> {

    Optional<BookingCancellation> findByBookingId(UUID bookingId);
}
