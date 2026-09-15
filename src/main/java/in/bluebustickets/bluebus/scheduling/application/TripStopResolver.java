package in.bluebustickets.bluebus.scheduling.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared TripStop ID → sequence resolution for customer trip APIs.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripStopResolver {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;

    public TripStopResolver(TripRepository tripRepository, TripStopRepository tripStopRepository) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
    }

    @Transactional(readOnly = true)
    public ResolvedSegment resolve(UUID tripId, UUID originStopId, UUID destinationStopId) {
        if (tripId == null) {
            throw new IllegalArgumentException("Trip id is required");
        }
        if (originStopId == null) {
            throw new IllegalArgumentException("Origin stop id is required");
        }
        if (destinationStopId == null) {
            throw new IllegalArgumentException("Destination stop id is required");
        }
        if (!tripRepository.existsById(tripId)) {
            throw new ResourceNotFoundException("Trip was not found.");
        }

        Map<UUID, TripStop> stopsById = tripStopRepository
                .findByTripIdAndIdIn(tripId, List.of(originStopId, destinationStopId))
                .stream()
                .collect(Collectors.toMap(TripStop::getId, Function.identity()));

        TripStop origin = stopsById.get(originStopId);
        if (origin == null) {
            throw new ResourceNotFoundException("Origin trip stop was not found for this trip.");
        }
        TripStop destination = stopsById.get(destinationStopId);
        if (destination == null) {
            throw new ResourceNotFoundException("Destination trip stop was not found for this trip.");
        }
        if (destination.getSequenceNumber() <= origin.getSequenceNumber()) {
            throw new IllegalArgumentException("Destination stop must be after the origin stop");
        }
        return new ResolvedSegment(origin, destination);
    }

    public record ResolvedSegment(TripStop origin, TripStop destination) {
        public int originSequence() {
            return origin.getSequenceNumber();
        }

        public int destinationSequence() {
            return destination.getSequenceNumber();
        }

        public UUID originStopId() {
            return origin.getId();
        }

        public UUID destinationStopId() {
            return destination.getId();
        }
    }
}
