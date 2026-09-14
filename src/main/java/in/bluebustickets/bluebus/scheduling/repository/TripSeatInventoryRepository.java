package in.bluebustickets.bluebus.scheduling.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripSeatInventoryRepository extends JpaRepository<TripSeatInventory, UUID> { }
