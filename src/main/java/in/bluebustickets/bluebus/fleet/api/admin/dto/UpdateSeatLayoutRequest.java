package in.bluebustickets.bluebus.fleet.api.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Metadata-only update. Seat definitions are not replaced through this endpoint. */
public record UpdateSeatLayoutRequest(
        @NotBlank @Size(max = 120) String name,
        @Min(1) int deckCount,
        @Min(1) int rowCount,
        @Min(1) int columnCount) {
}
