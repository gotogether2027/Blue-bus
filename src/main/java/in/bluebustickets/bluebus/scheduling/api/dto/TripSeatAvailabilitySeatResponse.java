package in.bluebustickets.bluebus.scheduling.api.dto;

import java.util.UUID;

import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.SeatAvailabilityResult;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;

public record TripSeatAvailabilitySeatResponse(
        UUID inventoryId,
        String seatNumber,
        String seatType,
        int deck,
        int row,
        int column,
        TripSeatInventoryStatus physicalStatus,
        JourneySeatAvailability availability) {

    public static TripSeatAvailabilitySeatResponse from(SeatAvailabilityResult result) {
        return new TripSeatAvailabilitySeatResponse(
                result.inventoryId(),
                result.seatNumber(),
                result.seatType(),
                result.deckNumber(),
                result.rowNumber(),
                result.columnNumber(),
                result.physicalStatus(),
                result.journeyAvailability());
    }
}
