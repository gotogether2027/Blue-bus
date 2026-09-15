package in.bluebustickets.bluebus.scheduling.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.SeatHoldStatus;

public record SeatHoldResponse(
        UUID holdId,
        UUID tripId,
        UUID originStopId,
        UUID destinationStopId,
        int originSequence,
        int destinationSequence,
        SeatHoldStatus status,
        Instant expiresAt,
        List<UUID> seatInventoryIds) {
}
