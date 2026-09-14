package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTripRequest(
        @NotNull UUID busId,
        @NotNull UUID routeId,
        @NotNull Instant scheduledDepartureAt,
        @NotNull Instant scheduledArrivalAt,
        @NotNull @DecimalMin("0.0") BigDecimal baseFare,
        @NotNull Instant bookingOpensAt,
        @NotNull Instant bookingClosesAt,
        @Size(max = 64) String timeZone) {
}
