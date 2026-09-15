package in.bluebustickets.bluebus.scheduling.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface TripSearchCandidate {
    UUID getTripId();
    UUID getOriginStopId();
    int getOriginSequence();
    UUID getDestinationStopId();
    int getDestinationSequence();
    LocalDate getServiceDate();
    Instant getScheduledDepartureAt();
}
