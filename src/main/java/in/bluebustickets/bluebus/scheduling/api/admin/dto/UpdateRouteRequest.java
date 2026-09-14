package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Metadata-only route update. Operator/code are immutable; stops/points are not replaced here. */
public record UpdateRouteRequest(
        @NotBlank @Size(max = 160) String name,
        @NotNull UUID sourceLocationId,
        @NotNull UUID destinationLocationId) {
}
