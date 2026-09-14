package in.bluebustickets.bluebus.scheduling.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.domain.TripStopStatus;

public record TripStopResponse(
        UUID id,
        UUID tripId,
        UUID routeStopId,
        UUID locationId,
        int sequenceNumber,
        StopKind stopKind,
        TripStopStatus stopStatus,
        Instant scheduledArrivalAt,
        Instant scheduledDepartureAt,
        BigDecimal distanceKm,
        List<TripPointResponse> points) {

    public static TripStopResponse from(TripStop stop, List<TripPoint> points) {
        return new TripStopResponse(
                stop.getId(),
                stop.getTripId(),
                stop.getRouteStopId(),
                stop.getLocation().getId(),
                stop.getSequenceNumber(),
                stop.getStopKind(),
                stop.getStopStatus(),
                stop.getScheduledArrivalAt(),
                stop.getScheduledDepartureAt(),
                stop.getDistanceKm(),
                points.stream().map(TripPointResponse::from).toList());
    }
}
