package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;

public record RouteStopResponse(
        UUID id,
        UUID routeId,
        UUID locationId,
        int sequenceNumber,
        StopKind stopKind,
        Integer arrivalOffsetMinutes,
        Integer departureOffsetMinutes,
        BigDecimal distanceKm,
        List<RoutePointResponse> points) {

    public static RouteStopResponse from(RouteStop stop, List<RoutePoint> points) {
        return new RouteStopResponse(
                stop.getId(),
                stop.getRoute().getId(),
                stop.getLocation().getId(),
                stop.getSequenceNumber(),
                stop.getStopKind(),
                stop.getArrivalOffsetMinutes(),
                stop.getDepartureOffsetMinutes(),
                stop.getDistanceKm(),
                points.stream().map(RoutePointResponse::from).toList());
    }
}
