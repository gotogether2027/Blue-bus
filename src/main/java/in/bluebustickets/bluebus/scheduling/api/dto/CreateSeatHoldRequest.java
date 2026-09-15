package in.bluebustickets.bluebus.scheduling.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSeatHoldRequest(
        @NotNull UUID originStopId,
        @NotNull UUID destinationStopId,
        @NotEmpty List<@NotNull UUID> seatInventoryIds,
        @Size(max = 100) String idempotencyKey) {
}
