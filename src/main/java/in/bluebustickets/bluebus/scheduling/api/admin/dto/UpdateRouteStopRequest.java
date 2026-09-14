package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateRouteStopRequest(
        @NotNull UUID locationId,
        @Min(1) int sequenceNumber,
        @NotNull StopKind stopKind,
        @Min(0) Integer arrivalOffsetMinutes,
        @Min(0) Integer departureOffsetMinutes,
        @DecimalMin("0.0") BigDecimal distanceKm) {
}
