package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateRouteStopRequest(
        @NotNull UUID locationId,
        @Min(1) int sequenceNumber,
        @NotNull StopKind stopKind,
        @Min(0) Integer arrivalOffsetMinutes,
        @Min(0) Integer departureOffsetMinutes,
        @DecimalMin("0.0") BigDecimal distanceKm,
        @Valid List<RoutePointDefinitionRequest> points) {
}
