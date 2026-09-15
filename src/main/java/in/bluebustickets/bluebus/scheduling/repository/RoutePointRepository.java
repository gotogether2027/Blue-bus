package in.bluebustickets.bluebus.scheduling.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePointRepository extends JpaRepository<RoutePoint, UUID> {

    List<RoutePoint> findByRouteStop_IdOrderByNameAsc(UUID routeStopId);

    List<RoutePoint> findByRouteStop_IdInOrderByNameAsc(Collection<UUID> routeStopIds);

    Optional<RoutePoint> findByIdAndRouteStop_Id(UUID id, UUID routeStopId);

    Optional<RoutePoint> findByIdAndRouteStop_Route_Id(UUID id, UUID routeId);

    boolean existsByRouteStop_IdAndNameIgnoreCase(UUID routeStopId, String name);
}
