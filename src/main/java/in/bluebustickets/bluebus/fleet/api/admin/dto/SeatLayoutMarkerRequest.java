package in.bluebustickets.bluebus.fleet.api.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SeatLayoutMarkerRequest(
        @NotBlank @Size(max = 30) String type,
        @Min(1) int deckNumber,
        @Min(1) int rowNumber,
        @Min(1) int columnNumber) {
}
