package in.bluebustickets.bluebus.scheduling.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPointRepository extends JpaRepository<TripPoint, UUID> {

    List<TripPoint> findByTripStop_IdInOrderByNameAsc(Collection<UUID> tripStopIds);

    long countByTripStop_TripId(UUID tripId);
}
