package in.bluebustickets.bluebus.scheduling.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutePointRepository extends JpaRepository<RoutePoint, UUID> { }
