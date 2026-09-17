package in.bluebustickets.bluebus.scheduling.application;

import in.bluebustickets.bluebus.foundation.api.error.ResourceNotFoundException;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSeatAvailabilityResponse;
import in.bluebustickets.bluebus.scheduling.api.dto.TripSeatAvailabilitySeatResponse;
import in.bluebustickets.bluebus.scheduling.application.TripStopResolver.ResolvedSegment;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

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
    private final TripStopResolver tripStopResolver;
    private final SeatAvailabilityService seatAvailabilityService;
    private final Clock clock;

    public TripSeatAvailabilityQueryService(
            TripRepository tripRepository,
            TripStopResolver tripStopResolver,
            SeatAvailabilityService seatAvailabilityService,
            Clock clock) {
        this.tripRepository = tripRepository;
        this.tripStopResolver = tripStopResolver;
        this.seatAvailabilityService = seatAvailabilityService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TripSeatAvailabilityResponse getAvailability(
            UUID tripId,
            UUID originStopId,
            UUID destinationStopId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip was not found."));
        TripSaleability.requireSaleableNow(trip, clock.instant());
        ResolvedSegment segment = tripStopResolver.resolve(tripId, originStopId, destinationStopId);
        List<TripSeatAvailabilitySeatResponse> seats = seatAvailabilityService
                .getSeatAvailability(tripId, segment.originSequence(), segment.destinationSequence())
                .stream()
                .map(TripSeatAvailabilitySeatResponse::from)
                .toList();

        return new TripSeatAvailabilityResponse(
                tripId,
                segment.originStopId(),
                segment.destinationStopId(),
                segment.originSequence(),
                segment.destinationSequence(),
                seats);
    }
}
