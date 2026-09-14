package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.SeatHold;
import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    List<SeatHold> findByTripIdOrderByCreatedAtDesc(UUID tripId);

    List<SeatHold> findByTripIdAndStatusOrderByCreatedAtDesc(UUID tripId, SeatHoldStatus status);

    Optional<SeatHold> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
