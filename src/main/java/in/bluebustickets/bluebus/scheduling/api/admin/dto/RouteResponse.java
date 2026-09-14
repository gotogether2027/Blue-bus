package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RouteStatus;

public record RouteResponse(
        UUID id,
        UUID operatorId,
        String code,
        String name,
        UUID sourceLocationId,
        UUID destinationLocationId,
        RouteStatus status,
        List<RouteStopResponse> stops) {

    public static RouteResponse from(Route route, List<RouteStopResponse> stops) {
        return new RouteResponse(
                route.getId(),
                route.getOperator().getId(),
                route.getCode(),
                route.getName(),
                route.getSourceLocation().getId(),
                route.getDestinationLocation().getId(),
                route.getStatus(),
                stops);
    }
}
