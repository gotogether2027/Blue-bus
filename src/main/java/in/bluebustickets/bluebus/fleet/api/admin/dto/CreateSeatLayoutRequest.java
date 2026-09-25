package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSeatLayoutRequest(
        @NotNull UUID operatorId,
        @NotBlank @Size(max = 120) String name,
        @Min(1) int version,
        @Min(1) int deckCount,
        @Min(1) int rowCount,
        @Min(1) int columnCount,
        @Valid List<SeatDefinitionRequest> seats,
        String layoutType,
        @Valid List<SeatLayoutMarkerRequest> markers) {
}
