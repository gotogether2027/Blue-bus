package in.bluebustickets.bluebus.scheduling.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripSeatAllocationRepository extends JpaRepository<TripSeatAllocation, UUID> {

    List<TripSeatAllocation> findByTripIdOrderByOriginSequenceAsc(UUID tripId);

    List<TripSeatAllocation> findByInventory_IdOrderByOriginSequenceAsc(UUID inventoryId);

    List<TripSeatAllocation> findByInventory_IdAndStateInOrderByOriginSequenceAsc(
            UUID inventoryId,
            Collection<TripSeatAllocationState> states);

    @Query(value = """
            SELECT COUNT(*) > 0
            FROM trip_seat_allocations a
            WHERE a.inventory_id = :inventoryId
              AND a.state IN ('HELD', 'BOOKED', 'BLOCKED')
              AND a.segment_range && int4range(:originSequence, :destinationSequence, '[)')
            """, nativeQuery = true)
    boolean existsActiveOverlap(
            @Param("inventoryId") UUID inventoryId,
            @Param("originSequence") int originSequence,
            @Param("destinationSequence") int destinationSequence);
}
