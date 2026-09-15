package in.bluebustickets.bluebus.scheduling.api.dto;

import java.util.List;
import java.util.UUID;

public record TripSeatAvailabilityResponse(
        UUID tripId,
        UUID originStopId,
        UUID destinationStopId,
        int originSequence,
        int destinationSequence,
        List<TripSeatAvailabilitySeatResponse> seats) {
}
