package in.bluebustickets.bluebus.fleet.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeatLayoutRepository extends JpaRepository<SeatLayout, UUID> { }
