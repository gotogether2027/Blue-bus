package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;

public record RoutePointResponse(
        UUID id,
        UUID routeStopId,
        String name,
        PointType pointType,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean active) {

    public static RoutePointResponse from(RoutePoint point) {
        return new RoutePointResponse(
                point.getId(),
                point.getRouteStop().getId(),
                point.getName(),
                point.getPointType(),
                point.getAddress(),
                point.getLatitude(),
                point.getLongitude(),
                point.isActive());
    }
}
