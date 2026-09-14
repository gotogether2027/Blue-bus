package in.bluebustickets.bluebus.fleet.api.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SeatDefinitionRequest(
        @NotBlank @Size(max = 20) String seatNumber,
        @Min(1) int deckNumber,
        @Min(1) int rowNumber,
        @Min(1) int columnNumber,
        @NotBlank @Size(max = 30) String seatType,
        Boolean sellable) {
}
