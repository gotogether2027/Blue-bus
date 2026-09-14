package in.bluebustickets.bluebus.scheduling.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RouteRepository extends JpaRepository<Route, UUID> { }
