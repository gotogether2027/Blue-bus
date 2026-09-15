package in.bluebustickets.bluebus.scheduling.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    boolean existsByBus_IdAndServiceDateAndScheduledDepartureAt(
            UUID busId, LocalDate serviceDate, Instant scheduledDepartureAt);

    Optional<Trip> findByIdAndOperator_Id(UUID id, UUID operatorId);

    List<Trip> findByOperator_IdOrderByScheduledDepartureAtAsc(UUID operatorId);

    List<Trip> findByOperator_IdAndServiceDateOrderByScheduledDepartureAtAsc(UUID operatorId, LocalDate serviceDate);

    List<Trip> findByOperator_IdAndStatusOrderByScheduledDepartureAtAsc(UUID operatorId, TripStatus status);

    List<Trip> findByOperator_IdAndServiceDateAndStatusOrderByScheduledDepartureAtAsc(
            UUID operatorId, LocalDate serviceDate, TripStatus status);

    List<Trip> findAllByOrderByScheduledDepartureAtAsc();

    List<Trip> findByBus_IdOrderByScheduledDepartureAtAsc(UUID busId);

    List<Trip> findByRoute_IdOrderByScheduledDepartureAtAsc(UUID routeId);

    List<Trip> findByServiceDateOrderByScheduledDepartureAtAsc(LocalDate serviceDate);

    List<Trip> findByStatusOrderByScheduledDepartureAtAsc(TripStatus status);

    List<Trip> findByBus_IdAndServiceDateOrderByScheduledDepartureAtAsc(UUID busId, LocalDate serviceDate);

    List<Trip> findByRoute_IdAndServiceDateOrderByScheduledDepartureAtAsc(UUID routeId, LocalDate serviceDate);

    List<Trip> findByBus_IdAndStatusOrderByScheduledDepartureAtAsc(UUID busId, TripStatus status);

    List<Trip> findByRoute_IdAndStatusOrderByScheduledDepartureAtAsc(UUID routeId, TripStatus status);

    List<Trip> findByServiceDateAndStatusOrderByScheduledDepartureAtAsc(LocalDate serviceDate, TripStatus status);

    List<Trip> findByBus_IdAndRoute_IdAndServiceDateAndStatusOrderByScheduledDepartureAtAsc(
            UUID busId, UUID routeId, LocalDate serviceDate, TripStatus status);

    @Query("""
            select distinct t from Trip t
            join fetch t.operator
            join fetch t.bus
            join fetch t.route
            where t.id in :ids
            """)
    List<Trip> findGraphByIdIn(@Param("ids") Collection<UUID> ids);

    @Query(value = """
            SELECT matches.trip_id AS "tripId",
                   matches.origin_stop_id AS "originStopId",
                   matches.origin_sequence AS "originSequence",
                   matches.destination_stop_id AS "destinationStopId",
                   matches.destination_sequence AS "destinationSequence",
                   matches.service_date AS "serviceDate",
                   matches.scheduled_departure_at AS "scheduledDepartureAt"
            FROM (
                SELECT DISTINCT ON (t.id)
                       t.id AS trip_id,
                       origin.id AS origin_stop_id,
                       origin.sequence_number AS origin_sequence,
                       destination.id AS destination_stop_id,
                       destination.sequence_number AS destination_sequence,
                       t.service_date,
                       t.scheduled_departure_at
                FROM trips t
                JOIN trip_stops origin
                  ON origin.trip_id = t.id
                 AND origin.location_id = :originLocationId
                 AND origin.stop_status = 'ACTIVE'
                JOIN trip_stops destination
                  ON destination.trip_id = t.id
                 AND destination.location_id = :destinationLocationId
                 AND destination.stop_status = 'ACTIVE'
                 AND origin.sequence_number < destination.sequence_number
                WHERE t.service_date = :serviceDate
                  AND t.status IN ('SCHEDULED', 'ON_SALE')
                ORDER BY t.id, origin.sequence_number, destination.sequence_number
            ) matches
            ORDER BY matches.scheduled_departure_at, matches.trip_id
            LIMIT 100
            """, nativeQuery = true)
    List<TripSearchCandidate> searchCustomerTrips(
            @Param("originLocationId") UUID originLocationId,
            @Param("destinationLocationId") UUID destinationLocationId,
            @Param("serviceDate") LocalDate serviceDate);
}
