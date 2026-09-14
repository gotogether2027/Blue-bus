package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.BusType;

public record BusTypeResponse(
        UUID id,
        String code,
        String displayName,
        boolean active) {

    public static BusTypeResponse from(BusType busType) {
        return new BusTypeResponse(
                busType.getId(),
                busType.getCode(),
                busType.getDisplayName(),
                busType.isActive());
    }
}
