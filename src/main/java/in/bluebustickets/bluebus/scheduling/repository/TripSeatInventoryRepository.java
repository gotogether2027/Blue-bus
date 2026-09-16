package in.bluebustickets.bluebus.scheduling.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripSeatInventoryRepository extends JpaRepository<TripSeatInventory, UUID> {

    List<TripSeatInventory> findByTrip_IdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(UUID tripId);

    List<TripSeatInventory> findByTrip_IdOrderBySeatNumberAsc(UUID tripId);

    Optional<TripSeatInventory> findByIdAndTrip_Id(UUID id, UUID tripId);

    long countByTrip_Id(UUID tripId);

    @Query(value = """
            SELECT COUNT(*)
            FROM trip_seat_inventory inventory
            WHERE inventory.trip_id = :tripId
              AND inventory.physical_status = 'AVAILABLE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM trip_seat_allocations allocation
                  WHERE allocation.inventory_id = inventory.id
                    AND allocation.state IN ('HELD', 'BOOKED', 'BLOCKED')
                    AND allocation.segment_range
                        && int4range(:originSequence, :destinationSequence, '[)')
              )
            """, nativeQuery = true)
    long countAvailableForSegment(
            @Param("tripId") UUID tripId,
            @Param("originSequence") int originSequence,
            @Param("destinationSequence") int destinationSequence);
}
