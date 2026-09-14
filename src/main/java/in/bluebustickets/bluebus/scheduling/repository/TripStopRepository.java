package in.bluebustickets.bluebus.scheduling.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripStopRepository extends JpaRepository<TripStop, UUID> { }
