package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripSeatInventoryRepository extends JpaRepository<TripSeatInventory, UUID> {

    List<TripSeatInventory> findByTrip_IdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(UUID tripId);

    List<TripSeatInventory> findByTrip_IdOrderBySeatNumberAsc(UUID tripId);

    long countByTrip_Id(UUID tripId);
}
