package in.bluebustickets.bluebus.fleet.repository;
import java.util.UUID;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SeatRepository extends JpaRepository<Seat, UUID> { }
