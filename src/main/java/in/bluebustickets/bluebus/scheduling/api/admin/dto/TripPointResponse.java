package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;

public record TripPointResponse(
        UUID id,
        UUID tripStopId,
        UUID sourceRoutePointId,
        String name,
        PointType pointType,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        boolean active) {

    public static TripPointResponse from(TripPoint point) {
        return new TripPointResponse(
                point.getId(),
                point.getTripStop().getId(),
                point.getSourceRoutePointId(),
                point.getName(),
                point.getPointType(),
                point.getAddress(),
                point.getLatitude(),
                point.getLongitude(),
                point.isActive());
    }
}
