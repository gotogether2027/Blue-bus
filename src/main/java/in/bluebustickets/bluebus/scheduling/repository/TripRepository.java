package in.bluebustickets.bluebus.scheduling.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    boolean existsByBus_IdAndServiceDateAndScheduledDepartureAt(
            UUID busId, LocalDate serviceDate, Instant scheduledDepartureAt);

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
}
