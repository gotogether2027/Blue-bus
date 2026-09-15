package in.bluebustickets.bluebus.scheduling.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSeatAvailabilityResponse;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSeatAvailabilitySeatResponse;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing read API for journey seat availability.
 * Resolves TripStop IDs to sequences, then delegates to {@link SeatAvailabilityService}.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class TripSeatAvailabilityQueryService {

    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final SeatAvailabilityService seatAvailabilityService;

    public TripSeatAvailabilityQueryService(
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            SeatAvailabilityService seatAvailabilityService) {
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.seatAvailabilityService = seatAvailabilityService;
    }

    @Transactional(readOnly = true)
    public TripSeatAvailabilityResponse getAvailability(
            UUID tripId,
            UUID originStopId,
            UUID destinationStopId) {
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

        int originSequence = origin.getSequenceNumber();
        int destinationSequence = destination.getSequenceNumber();
        if (destinationSequence <= originSequence) {
            throw new IllegalArgumentException("Destination stop must be after the origin stop");
        }

        List<TripSeatAvailabilitySeatResponse> seats = seatAvailabilityService
                .getSeatAvailability(tripId, originSequence, destinationSequence)
                .stream()
                .map(TripSeatAvailabilitySeatResponse::from)
                .toList();

        return new TripSeatAvailabilityResponse(
                tripId,
                originStopId,
                destinationStopId,
                originSequence,
                destinationSequence,
                seats);
    }
}
