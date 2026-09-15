package in.bluebustickets.bluebus.booking.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BookingPassengerRequest(
        @NotNull UUID seatInventoryId,
        @NotBlank @Size(max = 120) String fullName,
        @Min(0) @Max(120) Integer age,
        @Size(max = 30) String gender) {
}
