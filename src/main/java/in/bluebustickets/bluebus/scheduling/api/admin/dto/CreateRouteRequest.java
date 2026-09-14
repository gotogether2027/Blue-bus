package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRouteRequest(
        @NotNull UUID operatorId,
        @NotBlank @Size(max = 60) String code,
        @NotBlank @Size(max = 160) String name,
        @NotNull UUID sourceLocationId,
        @NotNull UUID destinationLocationId,
        @Valid List<RouteStopDefinitionRequest> stops) {
}
