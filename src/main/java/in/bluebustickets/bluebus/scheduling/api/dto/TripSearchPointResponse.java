package in.bluebustickets.bluebus.scheduling.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.domain.PointType;

public record TripSearchPointResponse(
        UUID pointId,
        String name,
        PointType pointType,
        String address) {
}
