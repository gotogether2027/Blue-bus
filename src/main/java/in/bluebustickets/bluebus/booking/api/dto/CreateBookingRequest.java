package in.bluebustickets.bluebus.booking.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBookingRequest(
        @NotNull UUID holdId,
        @NotNull UUID originStopId,
        @NotNull UUID destinationStopId,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotEmpty @Valid List<BookingPassengerRequest> passengers) {
}
