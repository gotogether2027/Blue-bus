package in.bluebustickets.bluebus.fleet.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.fleet.domain.BusType;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BusTypeRepository extends JpaRepository<BusType, UUID> { }
