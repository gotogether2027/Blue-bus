package in.bluebustickets.bluebus.scheduling.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {

    List<RouteStop> findByRoute_IdOrderBySequenceNumberAsc(UUID routeId);

    List<RouteStop> findByRoute_IdInOrderBySequenceNumberAsc(Collection<UUID> routeIds);

    Optional<RouteStop> findByIdAndRoute_Id(UUID id, UUID routeId);

    boolean existsByRoute_IdAndSequenceNumber(UUID routeId, int sequenceNumber);
}
