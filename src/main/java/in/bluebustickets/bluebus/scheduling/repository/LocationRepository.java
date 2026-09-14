package in.bluebustickets.bluebus.scheduling.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LocationRepository extends JpaRepository<Location, UUID> { }
