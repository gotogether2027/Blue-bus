package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Commercial-term update only. Bus, route, schedule, and snapshots are immutable after create.
 */
public record UpdateTripRequest(
        @NotNull @DecimalMin("0.0") BigDecimal baseFare,
        @NotNull Instant bookingOpensAt,
        @NotNull Instant bookingClosesAt) {
}
