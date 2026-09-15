package in.bluebustickets.bluebus.booking.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.PointType;

public record BookingTripPointResponse(
        UUID pointId,
        String name,
        PointType pointType,
        String address) {
}
