package in.bluebustickets.bluebus.fleet.api.admin.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusStatus;

public record BusResponse(
        UUID id,
        UUID operatorId,
        UUID busTypeId,
        UUID seatLayoutId,
        String registrationNumber,
        String displayName,
        BusStatus status) {

    public static BusResponse from(Bus bus) {
        return new BusResponse(
                bus.getId(),
                bus.getOperator().getId(),
                bus.getBusType().getId(),
                bus.getSeatLayout().getId(),
                bus.getRegistrationNumber(),
                bus.getDisplayName(),
                bus.getStatus());
    }
}
