package in.bluebustickets.bluebus.fleet.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.fleet.domain.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BusRepository extends JpaRepository<Bus, UUID> { }
