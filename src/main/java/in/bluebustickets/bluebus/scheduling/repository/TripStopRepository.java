package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStop, UUID> {

    List<TripStop> findByTripIdOrderBySequenceNumberAsc(UUID tripId);

    boolean existsByTripIdAndSequenceNumber(UUID tripId, int sequenceNumber);

    long countByTripId(UUID tripId);
}
