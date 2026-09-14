package in.bluebustickets.bluebus.scheduling.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> { }
