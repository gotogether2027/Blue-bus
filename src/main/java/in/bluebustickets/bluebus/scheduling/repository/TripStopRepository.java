package in.bluebustickets.bluebus.scheduling.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripStopRepository extends JpaRepository<TripStop, UUID> {

    List<TripStop> findByTripIdOrderBySequenceNumberAsc(UUID tripId);

    boolean existsByTripIdAndSequenceNumber(UUID tripId, int sequenceNumber);

    long countByTripId(UUID tripId);

    @Query("""
            select ts from TripStop ts
            where ts.tripId = :tripId and ts.id in :stopIds
            """)
    List<TripStop> findByTripIdAndIdIn(
            @Param("tripId") UUID tripId,
            @Param("stopIds") Collection<UUID> stopIds);
}
