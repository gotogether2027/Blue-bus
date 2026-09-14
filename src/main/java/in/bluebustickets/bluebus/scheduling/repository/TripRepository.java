package in.bluebustickets.bluebus.scheduling.repository;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> { }
